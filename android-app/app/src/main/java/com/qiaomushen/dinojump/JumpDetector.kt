package com.qiaomushen.dinojump

import kotlin.math.abs
import kotlin.math.min

/** Detects one complete takeoff-flight-landing cycle and locks out landing impacts. */
class JumpDetector(
    private var takeoffThreshold: Float = 2.8f,
    private val requiredTakeoffSamples: Int = 2,
    private val requiredFlightSamples: Int = 3,
    private val flightMagnitudeThreshold: Float = 4.5f,
    private val landingMagnitudeThreshold: Float = 14.0f,
    private val lateralImpulseThreshold: Float = 0.18f,
) {
    enum class State {
        READY,
        CANDIDATE,
        TAKEOFF,
        FLIGHT,
        LANDING,
        SETTLING,
    }

    data class Result(
        val state: State,
        val action: DetectedAction? = null,
    )

    private var state = State.READY
    private var stateEnteredNs = 0L
    private var lastTimestampNs: Long? = null
    private var takeoffSamples = 0
    private var flightSamples = 0
    private var stableSamples = 0
    private var lateralImpulse = 0f
    private var peakVerticalAcceleration = 0f

    val currentState: State
        get() = state

    val isBlockingLowerPriorityActions: Boolean
        get() =
            state == State.TAKEOFF ||
                state == State.FLIGHT ||
                state == State.LANDING ||
                state == State.SETTLING

    fun reset() {
        state = State.READY
        stateEnteredNs = 0L
        lastTimestampNs = null
        resetCandidate()
        stableSamples = 0
    }

    fun setTakeoffThreshold(value: Float) {
        require(value in 0.5f..15f) { "Threshold must be between 0.5 and 15 m/s²" }
        takeoffThreshold = value
    }

    fun update(sample: MotionSample, emitVerticalOnTakeoff: Boolean = false): Result {
        val dtSeconds = lastTimestampNs
            ?.let { ((sample.timestampNs - it).coerceIn(0L, 50_000_000L) / 1_000_000_000f) }
            ?: 0f
        lastTimestampNs = sample.timestampNs

        return when (state) {
            State.READY -> updateReady(sample, dtSeconds, emitVerticalOnTakeoff)
            State.CANDIDATE -> updateCandidate(sample, dtSeconds)
            State.TAKEOFF -> updateTakeoff(sample)
            State.FLIGHT -> updateFlight(sample)
            State.LANDING -> updateLanding(sample)
            State.SETTLING -> updateSettling(sample)
        }
    }

    private fun updateReady(
        sample: MotionSample,
        dtSeconds: Float,
        emitVerticalOnTakeoff: Boolean,
    ): Result {
        if (sample.verticalAcceleration >= takeoffThreshold) {
            takeoffSamples += 1
            lateralImpulse += sample.lateralAcceleration * dtSeconds
            peakVerticalAcceleration = maxOf(
                peakVerticalAcceleration,
                sample.verticalAcceleration,
            )
        } else {
            resetCandidate()
        }

        if (takeoffSamples >= requiredTakeoffSamples) {
            stateEnteredNs = sample.timestampNs
            if (emitVerticalOnTakeoff) {
                state = State.TAKEOFF
                return Result(
                    state,
                    buildAction(MotionAction.JUMP_UP, sample, includeLateralConfidence = false),
                )
            }
            state = State.CANDIDATE
        }
        return Result(state)
    }

    private fun updateCandidate(sample: MotionSample, dtSeconds: Float): Result {
        lateralImpulse += sample.lateralAcceleration * dtSeconds
        peakVerticalAcceleration = maxOf(
            peakVerticalAcceleration,
            sample.verticalAcceleration,
        )

        val looksAirborne = sample.rawAccelerationMagnitude <= flightMagnitudeThreshold
        flightSamples = if (looksAirborne) flightSamples + 1 else 0

        if (flightSamples >= requiredFlightSamples) {
            state = State.FLIGHT
            stateEnteredNs = sample.timestampNs
            val action = when {
                lateralImpulse <= -lateralImpulseThreshold -> MotionAction.JUMP_LEFT
                lateralImpulse >= lateralImpulseThreshold -> MotionAction.JUMP_RIGHT
                else -> MotionAction.JUMP_UP
            }
            return Result(
                state,
                buildAction(action, sample, includeLateralConfidence = true),
            )
        }

        if (sample.timestampNs - stateEnteredNs >= 300_000_000L) {
            state = State.READY
            resetCandidate()
        }
        return Result(state)
    }

    private fun updateTakeoff(sample: MotionSample): Result {
        val elapsedNs = sample.timestampNs - stateEnteredNs
        val looksAirborne = sample.rawAccelerationMagnitude <= flightMagnitudeThreshold
        flightSamples = if (looksAirborne) flightSamples + 1 else 0

        if (flightSamples >= requiredFlightSamples) {
            state = State.FLIGHT
            stateEnteredNs = sample.timestampNs
        } else if (
            elapsedNs >= 120_000_000L &&
            (sample.rawAccelerationMagnitude >= landingMagnitudeThreshold ||
                sample.verticalAcceleration >= 4.5f)
        ) {
            state = State.LANDING
            stateEnteredNs = sample.timestampNs
        } else if (elapsedNs >= 350_000_000L) {
            state = State.SETTLING
            stateEnteredNs = sample.timestampNs
            stableSamples = 0
        }
        return Result(state)
    }

    private fun updateFlight(sample: MotionSample): Result {
        val flightDurationNs = sample.timestampNs - stateEnteredNs
        val landed = flightDurationNs >= 50_000_000L && (
            sample.rawAccelerationMagnitude >= landingMagnitudeThreshold ||
                sample.verticalAcceleration >= 4.5f
            )
        if (landed) {
            state = State.LANDING
            stateEnteredNs = sample.timestampNs
        } else if (flightDurationNs >= 900_000_000L) {
            state = State.SETTLING
            stateEnteredNs = sample.timestampNs
        }
        return Result(state)
    }

    private fun updateLanding(sample: MotionSample): Result {
        if (sample.timestampNs - stateEnteredNs >= 40_000_000L) {
            state = State.SETTLING
            stateEnteredNs = sample.timestampNs
            stableSamples = 0
        }
        return Result(state)
    }

    private fun updateSettling(sample: MotionSample): Result {
        val stable =
            abs(sample.verticalAcceleration) < 1.1f &&
                abs(sample.lateralAcceleration) < 1.3f &&
                abs(sample.rawAccelerationMagnitude - 9.81f) < 1.6f &&
                sample.gyroscopeMagnitude < 0.8f
        stableSamples = if (stable) stableSamples + 1 else 0

        if (stableSamples >= 15 || sample.timestampNs - stateEnteredNs >= 1_500_000_000L) {
            state = State.READY
            resetCandidate()
            stableSamples = 0
        }
        return Result(state)
    }

    private fun resetCandidate() {
        takeoffSamples = 0
        flightSamples = 0
        lateralImpulse = 0f
        peakVerticalAcceleration = 0f
    }

    private fun buildAction(
        action: MotionAction,
        sample: MotionSample,
        includeLateralConfidence: Boolean,
    ): DetectedAction {
        val confidence = min(
            0.99f,
            0.70f +
                ((peakVerticalAcceleration - takeoffThreshold).coerceAtLeast(0f) * 0.04f) +
                (if (includeLateralConfidence) abs(lateralImpulse) * 0.08f else 0f),
        )
        return DetectedAction(
            action = action,
            phase = ActionPhase.TRIGGER,
            confidence = confidence,
            timestampNs = sample.timestampNs,
            verticalAcceleration = sample.verticalAcceleration,
            lateralAcceleration = sample.lateralAcceleration,
        )
    }
}
