package com.qiaomushen.dinojump

import kotlin.math.sqrt

/**
 * Orientation-independent V0 jump detector.
 *
 * The accelerometer vector is low-pass filtered to estimate gravity. The
 * remaining linear acceleration is projected onto the gravity direction.
 * Positive projected acceleration corresponds to an upward takeoff impulse.
 */
class JumpDetector(
    private var threshold: Float = 2.8f,
    private val calibrationDurationNs: Long = 1_500_000_000L,
    private val cooldownNs: Long = 850_000_000L,
    private val requiredConsecutiveSamples: Int = 2,
    private val gravityAlpha: Float = 0.98f,
) {
    enum class State {
        CALIBRATING,
        READY,
        COOLDOWN,
    }

    data class Result(
        val state: State,
        val verticalAcceleration: Float,
        val triggered: Boolean,
    )

    private var firstTimestampNs: Long? = null
    private var lastTriggerNs: Long? = null
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var gravityInitialized = false
    private var consecutiveSamples = 0

    fun reset() {
        firstTimestampNs = null
        lastTriggerNs = null
        gravityX = 0f
        gravityY = 0f
        gravityZ = 0f
        gravityInitialized = false
        consecutiveSamples = 0
    }

    fun setThreshold(value: Float) {
        require(value in 0.5f..15f) { "Threshold must be between 0.5 and 15 m/s²" }
        threshold = value
    }

    fun update(
        accelerationX: Float,
        accelerationY: Float,
        accelerationZ: Float,
        timestampNs: Long,
    ): Result {
        val startNs = firstTimestampNs ?: timestampNs.also { firstTimestampNs = it }

        if (!gravityInitialized) {
            gravityX = accelerationX
            gravityY = accelerationY
            gravityZ = accelerationZ
            gravityInitialized = true
        } else {
            val motionWeight = 1f - gravityAlpha
            gravityX = gravityAlpha * gravityX + motionWeight * accelerationX
            gravityY = gravityAlpha * gravityY + motionWeight * accelerationY
            gravityZ = gravityAlpha * gravityZ + motionWeight * accelerationZ
        }

        val linearX = accelerationX - gravityX
        val linearY = accelerationY - gravityY
        val linearZ = accelerationZ - gravityZ
        val gravityNorm = sqrt(
            gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ,
        ).coerceAtLeast(0.001f)

        val verticalAcceleration =
            (linearX * gravityX + linearY * gravityY + linearZ * gravityZ) / gravityNorm

        if (timestampNs - startNs < calibrationDurationNs) {
            consecutiveSamples = 0
            return Result(State.CALIBRATING, verticalAcceleration, false)
        }

        val previousTriggerNs = lastTriggerNs
        if (previousTriggerNs != null && timestampNs - previousTriggerNs < cooldownNs) {
            consecutiveSamples = 0
            return Result(State.COOLDOWN, verticalAcceleration, false)
        }

        consecutiveSamples = if (verticalAcceleration >= threshold) {
            consecutiveSamples + 1
        } else {
            0
        }

        if (consecutiveSamples >= requiredConsecutiveSamples) {
            consecutiveSamples = 0
            lastTriggerNs = timestampNs
            return Result(State.COOLDOWN, verticalAcceleration, true)
        }

        return Result(State.READY, verticalAcceleration, false)
    }
}
