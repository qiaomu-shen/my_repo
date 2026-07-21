package com.qiaomushen.dinojump

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpDetectorTest {
    @Test
    fun takeoffIsConfirmedByFlightAndLandingCannotRetrigger() {
        val detector = JumpDetector(takeoffThreshold = 2.0f)
        var timestamp = 0L

        detector.update(sample(timestamp, vertical = 3.5f, rawMagnitude = 13.3f))
        timestamp += STEP_NS
        detector.update(sample(timestamp, vertical = 3.5f, rawMagnitude = 13.3f))

        var triggered: DetectedAction? = null
        repeat(5) {
            timestamp += STEP_NS
            triggered = detector.update(
                sample(timestamp, vertical = -9.0f, rawMagnitude = 0.8f),
            ).action ?: triggered
        }

        assertEquals(MotionAction.JUMP_UP, triggered?.action)
        assertEquals(ActionPhase.TRIGGER, triggered?.phase)
        assertEquals(JumpDetector.State.FLIGHT, detector.currentState)

        timestamp += 60_000_000L
        val landing = detector.update(
            sample(timestamp, vertical = 8f, rawMagnitude = 18f),
        )
        assertNull(landing.action)
        assertEquals(JumpDetector.State.LANDING, landing.state)

        timestamp += 50_000_000L
        detector.update(sample(timestamp))
        repeat(15) {
            timestamp += STEP_NS
            assertNull(detector.update(sample(timestamp)).action)
        }
        assertEquals(JumpDetector.State.READY, detector.currentState)
    }

    @Test
    fun horizontalTakeoffImpulseClassifiesRightJump() {
        val detector = JumpDetector(takeoffThreshold = 2.0f)
        var timestamp = 0L
        detector.update(sample(timestamp, vertical = 4f, lateral = 8f, rawMagnitude = 13f))
        repeat(7) { index ->
            timestamp += STEP_NS
            val inFlight = index >= 1
            detector.update(
                sample(
                    timestamp,
                    vertical = if (inFlight) -9f else 4f,
                    lateral = 8f,
                    rawMagnitude = if (inFlight) 0.8f else 13f,
                ),
            ).action?.let {
                assertEquals(MotionAction.JUMP_RIGHT, it.action)
                return
            }
        }
        throw AssertionError("Expected a right jump")
    }

    @Test
    fun upwardImpulseWithoutFlightIsRejected() {
        val detector = JumpDetector(takeoffThreshold = 2.0f)
        var timestamp = 0L
        repeat(2) {
            detector.update(sample(timestamp, vertical = 3f, rawMagnitude = 12.8f))
            timestamp += STEP_NS
        }
        repeat(40) {
            val result = detector.update(sample(timestamp))
            assertNull(result.action)
            timestamp += STEP_NS
        }
        assertTrue(detector.currentState == JumpDetector.State.READY)
    }

    private fun sample(
        timestampNs: Long,
        vertical: Float = 0f,
        lateral: Float = 0f,
        rawMagnitude: Float = 9.81f,
    ) = MotionSample(
        timestampNs = timestampNs,
        verticalAcceleration = vertical,
        lateralAcceleration = lateral,
        forwardAcceleration = 0f,
        rawAccelerationMagnitude = rawMagnitude,
        gyroscopeMagnitude = 0f,
        tiltRadians = 0f,
    )

    private companion object {
        const val STEP_NS = 10_000_000L
    }
}
