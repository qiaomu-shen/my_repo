package com.qiaomushen.dinojump

import android.hardware.SensorManager
import kotlin.math.acos
import kotlin.math.sqrt

/** Converts asynchronous Android sensor events into one body-aligned motion sample. */
class SensorFrameTransformer {
    private val rotationMatrix = FloatArray(9)
    private var hasRotation = false
    private var gyroscopeX = 0f
    private var gyroscopeY = 0f
    private var gyroscopeZ = 0f
    private var gyroscopeMagnitude = 0f

    fun reset() {
        hasRotation = false
        gyroscopeX = 0f
        gyroscopeY = 0f
        gyroscopeZ = 0f
        gyroscopeMagnitude = 0f
    }

    fun updateRotationVector(values: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        hasRotation = true
    }

    fun updateGyroscope(x: Float, y: Float, z: Float) {
        gyroscopeX = x
        gyroscopeY = y
        gyroscopeZ = z
        gyroscopeMagnitude = sqrt(x * x + y * y + z * z)
    }

    fun transformAccelerometer(
        x: Float,
        y: Float,
        z: Float,
        timestampNs: Long,
    ): MotionSample? {
        if (!hasRotation) return null

        val worldX = rotationMatrix[0] * x + rotationMatrix[1] * y + rotationMatrix[2] * z
        val worldY = rotationMatrix[3] * x + rotationMatrix[4] * y + rotationMatrix[5] * z
        val worldZ = rotationMatrix[6] * x + rotationMatrix[7] * y + rotationMatrix[8] * z

        val rightX = rotationMatrix[0]
        val rightY = rotationMatrix[3]
        val rightNorm = sqrt(rightX * rightX + rightY * rightY).coerceAtLeast(0.001f)
        val lateralAcceleration = (worldX * rightX + worldY * rightY) / rightNorm

        val forwardX = rotationMatrix[2]
        val forwardY = rotationMatrix[5]
        val forwardNorm = sqrt(forwardX * forwardX + forwardY * forwardY).coerceAtLeast(0.001f)
        val forwardAcceleration = (worldX * forwardX + worldY * forwardY) / forwardNorm

        val deviceUpWorldZ = rotationMatrix[7].coerceIn(-1f, 1f)
        val tiltRadians = acos(deviceUpWorldZ)
        val rawMagnitude = sqrt(x * x + y * y + z * z)

        return MotionSample(
            timestampNs = timestampNs,
            verticalAcceleration = worldZ - SensorManager.GRAVITY_EARTH,
            lateralAcceleration = lateralAcceleration,
            forwardAcceleration = forwardAcceleration,
            rawAccelerationMagnitude = rawMagnitude,
            gyroscopeMagnitude = gyroscopeMagnitude,
            tiltRadians = tiltRadians,
            gyroscopeX = gyroscopeX,
            gyroscopeY = gyroscopeY,
            gyroscopeZ = gyroscopeZ,
        )
    }
}
