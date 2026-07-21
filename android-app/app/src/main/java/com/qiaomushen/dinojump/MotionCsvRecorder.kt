package com.qiaomushen.dinojump

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Records processed samples now so later models can be trained from real sessions. */
class MotionCsvRecorder : Closeable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var writer: BufferedWriter? = null

    fun start(directory: File): File {
        directory.mkdirs()
        val file = File(directory, "motion-${System.currentTimeMillis()}.csv")
        executor.execute {
            writer?.close()
            writer = file.bufferedWriter().also {
                it.appendLine(
                    "timestamp_ns,vertical_acceleration,lateral_acceleration," +
                        "forward_acceleration,raw_acceleration_magnitude,gyroscope_x,gyroscope_y," +
                        "gyroscope_z,gyroscope_magnitude,tilt_radians,state,running_confidence," +
                        "jump_state,squat_state,enabled_actions,detected_actions",
                )
            }
        }
        return file
    }

    fun record(result: RecognitionResult) {
        val sample = result.sample
        val actions = result.actions.joinToString("|") { "${it.action}:${it.phase}" }
        val enabledActions = result.enabledActions
            .sortedBy(MotionAction::ordinal)
            .joinToString("|")
        val line = String.format(
            Locale.US,
            "%d,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%s,%.5f,%s,%s,%s,%s",
            sample.timestampNs,
            sample.verticalAcceleration,
            sample.lateralAcceleration,
            sample.forwardAcceleration,
            sample.rawAccelerationMagnitude,
            sample.gyroscopeX,
            sample.gyroscopeY,
            sample.gyroscopeZ,
            sample.gyroscopeMagnitude,
            sample.tiltRadians,
            result.state,
            result.runningConfidence,
            result.jumpState,
            result.squatState,
            enabledActions,
            actions,
        )
        executor.execute { writer?.appendLine(line) }
    }

    fun stop() {
        executor.execute {
            writer?.flush()
            writer?.close()
            writer = null
        }
    }

    override fun close() {
        stop()
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
