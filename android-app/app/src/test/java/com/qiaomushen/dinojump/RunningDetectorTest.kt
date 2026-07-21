package com.qiaomushen.dinojump

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningDetectorTest {
    @Test
    fun periodicMotionStartsAndStopsRunningState() {
        val detector = RunningDetector()
        var timestamp = 0L
        var started = false

        repeat(300) {
            val seconds = timestamp / 1_000_000_000.0
            val vertical = (2.4 * sin(2.0 * PI * 2.5 * seconds)).toFloat()
            val result = detector.update(sample(timestamp, vertical))
            started = started || result.transition == ActionPhase.START
            timestamp += STEP_NS
        }

        assertTrue(started)
        assertTrue(detector.isActive)

        var stopped = false
        repeat(180) {
            val result = detector.update(sample(timestamp, 0f))
            stopped = stopped || result.transition == ActionPhase.STOP
            timestamp += STEP_NS
        }
        assertTrue(stopped)
        assertFalse(detector.isActive)
    }

    private fun sample(timestampNs: Long, vertical: Float) = MotionSample(
        timestampNs = timestampNs,
        verticalAcceleration = vertical,
        lateralAcceleration = 0f,
        forwardAcceleration = 0f,
        rawAccelerationMagnitude = 9.81f + vertical.coerceAtLeast(0f),
        gyroscopeMagnitude = 0.5f,
        tiltRadians = 0f,
    )

    private companion object {
        const val STEP_NS = 10_000_000L
    }
}
