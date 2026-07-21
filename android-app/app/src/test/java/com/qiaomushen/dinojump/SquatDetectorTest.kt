package com.qiaomushen.dinojump

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SquatDetectorTest {
    @Test
    fun descentBottomAndRiseProduceStartAndStop() {
        val detector = SquatDetector()
        var timestamp = 0L
        var started = false

        repeat(25) {
            val result = detector.update(sample(timestamp, vertical = -1.2f), true)
            started = started || result.transition == ActionPhase.START
            timestamp += STEP_NS
        }
        repeat(25) {
            val result = detector.update(sample(timestamp, vertical = 1.2f), true)
            started = started || result.transition == ActionPhase.START
            timestamp += STEP_NS
        }

        assertTrue(started)
        assertTrue(detector.isActive)

        detector.update(sample(timestamp, vertical = 1.0f), true)
        timestamp += STEP_NS
        var stopped = false
        repeat(30) {
            val result = detector.update(sample(timestamp), true)
            stopped = stopped || result.transition == ActionPhase.STOP
            timestamp += STEP_NS
        }

        assertTrue(stopped)
        assertEquals(SquatDetector.State.STANDING, detector.currentState)
    }

    private fun sample(timestampNs: Long, vertical: Float = 0f) = MotionSample(
        timestampNs = timestampNs,
        verticalAcceleration = vertical,
        lateralAcceleration = 0f,
        forwardAcceleration = 0f,
        rawAccelerationMagnitude = 9.81f + vertical,
        gyroscopeMagnitude = 0f,
        tiltRadians = 0f,
    )

    private companion object {
        const val STEP_NS = 10_000_000L
    }
}
