package com.qiaomushen.dinojump

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt

/** Detects sustained periodic stepping; normal running and high knees share this class. */
class RunningDetector(
    private val peakThreshold: Float = 1.35f,
    private val windowNs: Long = 1_300_000_000L,
    private val enterHoldNs: Long = 220_000_000L,
    private val exitHoldNs: Long = 400_000_000L,
) {
    data class Result(
        val active: Boolean,
        val transition: ActionPhase? = null,
        val confidence: Float,
    )

    private data class EnergySample(val timestampNs: Long, val energySquared: Float)
    private data class PeakSample(val timestampNs: Long, val value: Float)

    private val energySamples = ArrayDeque<EnergySample>()
    private val peaks = ArrayDeque<Long>()
    private var previousPreviousPeakSample: PeakSample? = null
    private var previousPeakSample: PeakSample? = null
    private var candidateSinceNs: Long? = null
    private var inactiveSinceNs: Long? = null
    private var active = false
    private var confidence = 0f

    val isActive: Boolean
        get() = active

    val currentConfidence: Float
        get() = confidence

    fun reset() {
        energySamples.clear()
        peaks.clear()
        previousPreviousPeakSample = null
        previousPeakSample = null
        candidateSinceNs = null
        inactiveSinceNs = null
        active = false
        confidence = 0f
    }

    fun update(sample: MotionSample): Result {
        val energySquared =
            sample.verticalAcceleration * sample.verticalAcceleration +
                0.35f * sample.lateralAcceleration * sample.lateralAcceleration +
                0.15f * sample.forwardAcceleration * sample.forwardAcceleration
        energySamples.addLast(EnergySample(sample.timestampNs, energySquared))
        while (energySamples.isNotEmpty() && sample.timestampNs - energySamples.first.timestampNs > windowNs) {
            energySamples.removeFirst()
        }

        val peakSignal = maxOf(
            sample.verticalAcceleration,
            sample.rawAccelerationMagnitude - 9.81f,
        )
        val currentPeakSample = PeakSample(sample.timestampNs, peakSignal)
        val left = previousPreviousPeakSample
        val middle = previousPeakSample
        if (
            left != null && middle != null &&
            middle.value >= peakThreshold && middle.value > left.value && middle.value >= currentPeakSample.value
        ) {
            val farEnoughFromPrevious = peaks.isEmpty() || middle.timestampNs - peaks.last >= 160_000_000L
            if (farEnoughFromPrevious) peaks.addLast(middle.timestampNs)
        }
        previousPreviousPeakSample = middle
        previousPeakSample = currentPeakSample

        while (peaks.isNotEmpty() && sample.timestampNs - peaks.first > windowNs) {
            peaks.removeFirst()
        }

        val rms = if (energySamples.isEmpty()) {
            0f
        } else {
            sqrt(energySamples.sumOf { it.energySquared.toDouble() }.toFloat() / energySamples.size)
        }
        val peakTimes = peaks.toList()
        val intervals = peakTimes.zipWithNext { a, b -> b - a }
        val intervalsValid = intervals.size >= 2 && intervals.all { it in 160_000_000L..750_000_000L }
        val regular = if (intervalsValid) {
            val mean = intervals.average()
            intervals.maxOf { abs(it - mean) } / mean <= 0.45
        } else {
            false
        }
        val latestPeakIsRecent = peaks.isNotEmpty() && sample.timestampNs - peaks.last <= 450_000_000L
        val periodic = regular && latestPeakIsRecent && rms >= 0.85f

        confidence = when {
            !periodic -> (confidence * 0.9f).coerceAtLeast(0f)
            else -> (0.58f + (peakTimes.size - 3).coerceAtLeast(0) * 0.08f + (rms - 0.85f) * 0.06f)
                .coerceIn(0f, 0.97f)
        }

        if (!active) {
            if (periodic) {
                val since = candidateSinceNs ?: sample.timestampNs.also { candidateSinceNs = it }
                if (sample.timestampNs - since >= enterHoldNs) {
                    active = true
                    candidateSinceNs = null
                    inactiveSinceNs = null
                    return Result(true, ActionPhase.START, confidence)
                }
            } else {
                candidateSinceNs = null
            }
            return Result(false, confidence = confidence)
        }

        if (periodic) {
            inactiveSinceNs = null
        } else {
            val since = inactiveSinceNs ?: sample.timestampNs.also { inactiveSinceNs = it }
            if (sample.timestampNs - since >= exitHoldNs) {
                active = false
                inactiveSinceNs = null
                candidateSinceNs = null
                return Result(false, ActionPhase.STOP, confidence)
            }
        }
        return Result(active, confidence = confidence)
    }
}
