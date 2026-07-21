package com.qiaomushen.dinojump

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionCsvRecorderTest {
    @Test
    fun writesStableTrainingColumns() {
        val directory = Files.createTempDirectory("motion-recorder-test").toFile()
        val recorder = MotionCsvRecorder()
        val file = recorder.start(directory)
        val sample = MotionSample(
            timestampNs = 10L,
            verticalAcceleration = 1f,
            lateralAcceleration = 2f,
            forwardAcceleration = 3f,
            rawAccelerationMagnitude = 9.81f,
            gyroscopeMagnitude = 0.4f,
            tiltRadians = 0.1f,
            gyroscopeX = 0.2f,
            gyroscopeY = 0.3f,
            gyroscopeZ = 0.1f,
        )
        recorder.record(
            RecognitionResult(
                state = MotionState.RUNNING,
                sample = sample,
                actions = emptyList(),
                runningConfidence = 0.8f,
                jumpState = JumpDetector.State.READY,
                squatState = SquatDetector.State.STANDING,
                enabledActions = setOf(MotionAction.RUNNING),
            ),
        )
        recorder.close()

        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertEquals(16, lines[0].split(',').size)
        assertEquals(16, lines[1].split(',').size)
        assertEquals("RUNNING", lines[1].split(',')[14])
    }
}
