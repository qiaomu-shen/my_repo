package com.qiaomushen.dinojump

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpDetectorTest {
    @Test
    fun takeoffTriggersOnceAndLandingIsSuppressedByCooldown() {
        val detector = JumpDetector(threshold = 2.0f)
        var timestamp = 0L

        repeat(170) {
            val result = detector.update(0f, 0f, 9.81f, timestamp)
            assertFalse(result.triggered)
            timestamp += 10_000_000L
        }

        val firstTakeoffSample = detector.update(0f, 0f, 13.5f, timestamp)
        timestamp += 10_000_000L
        val secondTakeoffSample = detector.update(0f, 0f, 13.5f, timestamp)

        assertFalse(firstTakeoffSample.triggered)
        assertTrue(secondTakeoffSample.triggered)
        assertEquals(JumpDetector.State.COOLDOWN, secondTakeoffSample.state)

        timestamp += 300_000_000L
        val landingImpact = detector.update(0f, 0f, 15f, timestamp)
        assertFalse(landingImpact.triggered)
        assertEquals(JumpDetector.State.COOLDOWN, landingImpact.state)
    }
}
