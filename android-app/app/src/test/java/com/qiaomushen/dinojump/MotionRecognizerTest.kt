package com.qiaomushen.dinojump

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRecognizerTest {
    @Test
    fun calibratedPipelineEmitsOneConfirmedJumpAction() {
        val recognizer = MotionRecognizer(calibrationDurationNs = 0L)
        var timestamp = 0L
        recognizer.update(sample(timestamp))

        timestamp += STEP_NS
        recognizer.update(sample(timestamp, vertical = 4f, rawMagnitude = 13f))
        timestamp += STEP_NS
        recognizer.update(sample(timestamp, vertical = 4f, rawMagnitude = 13f))

        val actions = mutableListOf<DetectedAction>()
        repeat(5) {
            timestamp += STEP_NS
            actions += recognizer.update(
                sample(timestamp, vertical = -9f, rawMagnitude = 0.7f),
            ).actions
        }

        assertEquals(1, actions.size)
        assertEquals(MotionAction.JUMP_UP, actions.single().action)
        assertEquals(ActionPhase.TRIGGER, actions.single().phase)
        assertTrue(actions.single().confidence > 0.5f)
    }

    @Test
    fun verticalOnlyActionSpaceEmitsWithoutWaitingForFlight() {
        val recognizer = MotionRecognizer(calibrationDurationNs = 0L)
        recognizer.setEnabledActions(setOf(MotionAction.JUMP_UP))
        var timestamp = 0L
        recognizer.update(sample(timestamp))

        timestamp += STEP_NS
        val first = recognizer.update(sample(timestamp, vertical = 4f, rawMagnitude = 13f))
        timestamp += STEP_NS
        val second = recognizer.update(sample(timestamp, vertical = 4f, rawMagnitude = 13f))

        assertTrue(first.actions.isEmpty())
        assertEquals(MotionAction.JUMP_UP, second.actions.single().action)
        assertEquals(MotionState.JUMP, second.state)
    }

    @Test
    fun actionOutsideSelectedSpaceIsNotEmitted() {
        val recognizer = MotionRecognizer(calibrationDurationNs = 0L)
        recognizer.setEnabledActions(setOf(MotionAction.RUNNING))
        var timestamp = 0L
        recognizer.update(sample(timestamp))

        val actions = mutableListOf<DetectedAction>()
        repeat(2) {
            timestamp += STEP_NS
            actions += recognizer.update(
                sample(timestamp, vertical = 4f, rawMagnitude = 13f),
            ).actions
        }
        repeat(8) {
            timestamp += STEP_NS
            actions += recognizer.update(
                sample(timestamp, vertical = -9f, rawMagnitude = 0.7f),
            ).actions
        }

        assertTrue(actions.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyActionSpaceIsRejected() {
        MotionRecognizer().setEnabledActions(emptySet())
    }

    private fun sample(
        timestampNs: Long,
        vertical: Float = 0f,
        rawMagnitude: Float = 9.81f,
    ) = MotionSample(
        timestampNs = timestampNs,
        verticalAcceleration = vertical,
        lateralAcceleration = 0f,
        forwardAcceleration = 0f,
        rawAccelerationMagnitude = rawMagnitude,
        gyroscopeMagnitude = 0f,
        tiltRadians = 0f,
    )

    private companion object {
        const val STEP_NS = 10_000_000L
    }
}
