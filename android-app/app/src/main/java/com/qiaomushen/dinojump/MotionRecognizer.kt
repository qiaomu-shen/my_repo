package com.qiaomushen.dinojump

import kotlin.math.abs

/** Coordinates the rule experts and exposes the contract a future neural model will keep. */
class MotionRecognizer(
    private val calibrationDurationNs: Long = 1_500_000_000L,
    private val heartbeatIntervalNs: Long = 500_000_000L,
    private val jumpDetector: JumpDetector = JumpDetector(),
    private val runningDetector: RunningDetector = RunningDetector(),
    private val squatDetector: SquatDetector = SquatDetector(),
) {
    private var calibrationStartNs: Long? = null
    private var calibrationSamples = 0
    private var verticalOffsetSum = 0.0
    private var lateralOffsetSum = 0.0
    private var forwardOffsetSum = 0.0
    private var tiltSum = 0.0
    private var verticalOffset = 0f
    private var lateralOffset = 0f
    private var forwardOffset = 0f
    private var baselineTilt = 0f
    private var calibrated = false
    private var enabledActions: Set<MotionAction> = MotionAction.entries.toSet()
    private val lastHeartbeatByAction = mutableMapOf<MotionAction, Long>()

    fun reset() {
        calibrationStartNs = null
        calibrationSamples = 0
        verticalOffsetSum = 0.0
        lateralOffsetSum = 0.0
        forwardOffsetSum = 0.0
        tiltSum = 0.0
        verticalOffset = 0f
        lateralOffset = 0f
        forwardOffset = 0f
        baselineTilt = 0f
        calibrated = false
        lastHeartbeatByAction.clear()
        jumpDetector.reset()
        runningDetector.reset()
        squatDetector.reset()
    }

    fun setTakeoffThreshold(value: Float) {
        jumpDetector.setTakeoffThreshold(value)
    }

    fun setEnabledActions(actions: Set<MotionAction>) {
        require(actions.isNotEmpty()) { "At least one action must be enabled" }
        enabledActions = actions.toSet()
    }

    fun update(rawSample: MotionSample): RecognitionResult {
        if (!calibrated) return updateCalibration(rawSample)

        val sample = rawSample.copy(
            verticalAcceleration = rawSample.verticalAcceleration - verticalOffset,
            lateralAcceleration = rawSample.lateralAcceleration - lateralOffset,
            forwardAcceleration = rawSample.forwardAcceleration - forwardOffset,
            tiltRadians = abs(rawSample.tiltRadians - baselineTilt),
        )
        val actions = mutableListOf<DetectedAction>()
        val jumpEnabled = enabledActions.any { it in JUMP_ACTIONS }
        val jumpResult = if (jumpEnabled) {
            jumpDetector.update(sample)
        } else {
            jumpDetector.reset()
            JumpDetector.Result(JumpDetector.State.READY)
        }

        if (jumpResult.action != null) {
            stopContinuousActions(sample, actions)
            if (jumpResult.action.action in enabledActions) {
                actions += jumpResult.action
            }
        } else if (jumpDetector.isBlockingLowerPriorityActions) {
            runningDetector.reset()
            squatDetector.reset()
        } else {
            val squatResult = if (MotionAction.SQUAT in enabledActions) {
                squatDetector.update(
                    sample,
                    allowAccelerationTrigger = !runningDetector.isActive,
                )
            } else {
                squatDetector.reset()
                SquatDetector.Result(SquatDetector.State.STANDING)
            }
            when (squatResult.transition) {
                ActionPhase.START -> {
                    if (runningDetector.isActive) {
                        actions += continuousAction(sample, MotionAction.RUNNING, ActionPhase.STOP, 1f)
                        lastHeartbeatByAction.remove(MotionAction.RUNNING)
                        runningDetector.reset()
                    }
                    actions += continuousAction(
                        sample,
                        MotionAction.SQUAT,
                        ActionPhase.START,
                        squatResult.confidence,
                    )
                    lastHeartbeatByAction[MotionAction.SQUAT] = sample.timestampNs
                }
                ActionPhase.STOP -> {
                    actions += continuousAction(
                        sample,
                        MotionAction.SQUAT,
                        ActionPhase.STOP,
                        squatResult.confidence,
                    )
                    lastHeartbeatByAction.remove(MotionAction.SQUAT)
                }
                else -> Unit
            }

            if (!squatDetector.isActive && MotionAction.RUNNING in enabledActions) {
                val runningResult = runningDetector.update(sample)
                when (runningResult.transition) {
                    ActionPhase.START -> {
                        actions += continuousAction(
                            sample,
                            MotionAction.RUNNING,
                            ActionPhase.START,
                            runningResult.confidence,
                        )
                        lastHeartbeatByAction[MotionAction.RUNNING] = sample.timestampNs
                    }
                    ActionPhase.STOP -> {
                        actions += continuousAction(
                            sample,
                            MotionAction.RUNNING,
                            ActionPhase.STOP,
                            runningResult.confidence,
                        )
                        lastHeartbeatByAction.remove(MotionAction.RUNNING)
                    }
                    else -> Unit
                }
            } else if (MotionAction.RUNNING !in enabledActions) {
                runningDetector.reset()
            }
        }

        addHeartbeatIfDue(sample, MotionAction.SQUAT, squatDetector.isActive, actions)
        addHeartbeatIfDue(sample, MotionAction.RUNNING, runningDetector.isActive, actions)

        return RecognitionResult(
            state = currentMotionState(),
            sample = sample,
            actions = actions,
            runningConfidence = runningDetector.currentConfidence,
            jumpState = jumpDetector.currentState,
            squatState = squatDetector.currentState,
            enabledActions = enabledActions,
        )
    }

    fun stop(timestampNs: Long): List<DetectedAction> {
        val sample = MotionSample(timestampNs, 0f, 0f, 0f, 9.81f, 0f, 0f)
        val actions = mutableListOf<DetectedAction>()
        stopContinuousActions(sample, actions)
        reset()
        return actions
    }

    private fun updateCalibration(sample: MotionSample): RecognitionResult {
        val startNs = calibrationStartNs ?: sample.timestampNs.also { calibrationStartNs = it }
        verticalOffsetSum += sample.verticalAcceleration
        lateralOffsetSum += sample.lateralAcceleration
        forwardOffsetSum += sample.forwardAcceleration
        tiltSum += sample.tiltRadians
        calibrationSamples += 1

        if (sample.timestampNs - startNs >= calibrationDurationNs && calibrationSamples > 0) {
            verticalOffset = (verticalOffsetSum / calibrationSamples).toFloat()
            lateralOffset = (lateralOffsetSum / calibrationSamples).toFloat()
            forwardOffset = (forwardOffsetSum / calibrationSamples).toFloat()
            baselineTilt = (tiltSum / calibrationSamples).toFloat()
            calibrated = true
            jumpDetector.reset()
            runningDetector.reset()
            squatDetector.reset()
        }

        return RecognitionResult(
            state = if (calibrated) MotionState.READY else MotionState.CALIBRATING,
            sample = sample,
            actions = emptyList(),
            runningConfidence = 0f,
            jumpState = jumpDetector.currentState,
            squatState = squatDetector.currentState,
            enabledActions = enabledActions,
        )
    }

    private fun stopContinuousActions(sample: MotionSample, actions: MutableList<DetectedAction>) {
        if (squatDetector.isActive) {
            actions += continuousAction(sample, MotionAction.SQUAT, ActionPhase.STOP, 1f)
        }
        if (runningDetector.isActive) {
            actions += continuousAction(sample, MotionAction.RUNNING, ActionPhase.STOP, 1f)
        }
        lastHeartbeatByAction.clear()
        squatDetector.reset()
        runningDetector.reset()
    }

    private fun addHeartbeatIfDue(
        sample: MotionSample,
        action: MotionAction,
        active: Boolean,
        actions: MutableList<DetectedAction>,
    ) {
        if (!active || actions.any { it.action == action }) return
        val previousNs = lastHeartbeatByAction[action] ?: sample.timestampNs.also {
            lastHeartbeatByAction[action] = it
        }
        if (sample.timestampNs - previousNs >= heartbeatIntervalNs) {
            actions += continuousAction(sample, action, ActionPhase.UPDATE, 0.8f)
            lastHeartbeatByAction[action] = sample.timestampNs
        }
    }

    private fun continuousAction(
        sample: MotionSample,
        action: MotionAction,
        phase: ActionPhase,
        confidence: Float,
    ) = DetectedAction(
        action = action,
        phase = phase,
        confidence = confidence.coerceIn(0f, 1f),
        timestampNs = sample.timestampNs,
        verticalAcceleration = sample.verticalAcceleration,
        lateralAcceleration = sample.lateralAcceleration,
    )

    private fun currentMotionState(): MotionState = when {
        jumpDetector.isBlockingLowerPriorityActions -> MotionState.JUMP
        squatDetector.isActive -> MotionState.SQUAT
        runningDetector.isActive -> MotionState.RUNNING
        else -> MotionState.READY
    }

    private companion object {
        val JUMP_ACTIONS = setOf(
            MotionAction.JUMP_UP,
            MotionAction.JUMP_LEFT,
            MotionAction.JUMP_RIGHT,
        )
    }
}
