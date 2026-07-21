package com.qiaomushen.dinojump

import kotlin.math.abs

/** Detects a complete stand-descend-bottom-rise-stand sequence. */
class SquatDetector {
    enum class State {
        STANDING,
        CANDIDATE,
        ACTIVE,
        RISING,
    }

    data class Result(
        val state: State,
        val transition: ActionPhase? = null,
        val confidence: Float = 0f,
    )

    private var state = State.STANDING
    private var stateEnteredNs = 0L
    private var lastTimestampNs: Long? = null
    private var velocity = 0f
    private var displacement = 0f
    private var minimumVelocity = 0f
    private var maximumTilt = 0f
    private var stableSamples = 0

    val currentState: State
        get() = state

    val isActive: Boolean
        get() = state == State.ACTIVE || state == State.RISING

    fun reset() {
        state = State.STANDING
        stateEnteredNs = 0L
        lastTimestampNs = null
        velocity = 0f
        displacement = 0f
        minimumVelocity = 0f
        maximumTilt = 0f
        stableSamples = 0
    }

    fun update(sample: MotionSample, allowAccelerationTrigger: Boolean): Result {
        val dtSeconds = lastTimestampNs
            ?.let { ((sample.timestampNs - it).coerceIn(0L, 50_000_000L) / 1_000_000_000f) }
            ?: 0f
        lastTimestampNs = sample.timestampNs

        return when (state) {
            State.STANDING -> updateStanding(sample, allowAccelerationTrigger)
            State.CANDIDATE -> updateCandidate(sample, dtSeconds)
            State.ACTIVE -> updateActive(sample)
            State.RISING -> updateRising(sample, dtSeconds)
        }
    }

    private fun updateStanding(sample: MotionSample, allowAccelerationTrigger: Boolean): Result {
        val downwardStart = allowAccelerationTrigger && sample.verticalAcceleration <= -0.75f
        val postureStart = sample.tiltRadians >= 0.30f
        if (downwardStart || postureStart) {
            state = State.CANDIDATE
            stateEnteredNs = sample.timestampNs
            velocity = 0f
            displacement = 0f
            minimumVelocity = 0f
            maximumTilt = sample.tiltRadians
        }
        return Result(state)
    }

    private fun updateCandidate(sample: MotionSample, dtSeconds: Float): Result {
        velocity += sample.verticalAcceleration * dtSeconds
        displacement += velocity * dtSeconds
        minimumVelocity = minOf(minimumVelocity, velocity)
        maximumTilt = maxOf(maximumTilt, sample.tiltRadians)
        val elapsedNs = sample.timestampNs - stateEnteredNs

        val postureConfirmed = maximumTilt >= 0.48f && elapsedNs >= 180_000_000L
        val descentConfirmed =
            minimumVelocity <= -0.16f &&
                displacement <= -0.045f &&
                velocity >= minimumVelocity + 0.10f &&
                elapsedNs >= 250_000_000L
        if (postureConfirmed || descentConfirmed) {
            state = State.ACTIVE
            stateEnteredNs = sample.timestampNs
            velocity = 0f
            stableSamples = 0
            val confidence = if (postureConfirmed && descentConfirmed) 0.92f else 0.76f
            return Result(state, ActionPhase.START, confidence)
        }

        val quiet = abs(sample.verticalAcceleration) < 0.45f && sample.gyroscopeMagnitude < 0.35f
        if ((quiet && elapsedNs >= 350_000_000L && minimumVelocity > -0.16f) || elapsedNs >= 1_800_000_000L) {
            resetToStanding(sample.timestampNs)
        }
        return Result(state)
    }

    private fun updateActive(sample: MotionSample): Result {
        maximumTilt = maxOf(maximumTilt, sample.tiltRadians)
        val postureRising =
            maximumTilt >= 0.48f &&
                sample.tiltRadians <= maximumTilt - 0.12f &&
                sample.gyroscopeMagnitude >= 0.12f
        if (sample.verticalAcceleration >= 0.70f || postureRising) {
            state = State.RISING
            stateEnteredNs = sample.timestampNs
            velocity = 0f
            stableSamples = 0
        }
        return Result(state, confidence = 0.8f)
    }

    private fun updateRising(sample: MotionSample, dtSeconds: Float): Result {
        velocity += sample.verticalAcceleration * dtSeconds
        val elapsedNs = sample.timestampNs - stateEnteredNs
        val upright = sample.tiltRadians <= 0.20f
        val stable =
            upright &&
                abs(sample.verticalAcceleration) < 0.65f &&
                abs(sample.lateralAcceleration) < 0.9f &&
                sample.gyroscopeMagnitude < 0.45f
        stableSamples = if (stable) stableSamples + 1 else 0

        if (stableSamples >= 15 && elapsedNs >= 220_000_000L) {
            resetToStanding(sample.timestampNs)
            return Result(State.STANDING, ActionPhase.STOP, 0.86f)
        }
        if (elapsedNs >= 3_500_000_000L && stable) {
            resetToStanding(sample.timestampNs)
            return Result(State.STANDING, ActionPhase.STOP, 0.65f)
        }
        return Result(state, confidence = 0.8f)
    }

    private fun resetToStanding(timestampNs: Long) {
        state = State.STANDING
        stateEnteredNs = timestampNs
        velocity = 0f
        displacement = 0f
        minimumVelocity = 0f
        maximumTilt = 0f
        stableSamples = 0
    }
}
