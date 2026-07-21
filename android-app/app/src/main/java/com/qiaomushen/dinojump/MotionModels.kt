package com.qiaomushen.dinojump

data class MotionSample(
    val timestampNs: Long,
    val verticalAcceleration: Float,
    val lateralAcceleration: Float,
    val forwardAcceleration: Float,
    val rawAccelerationMagnitude: Float,
    val gyroscopeMagnitude: Float,
    val tiltRadians: Float,
    val gyroscopeX: Float = 0f,
    val gyroscopeY: Float = 0f,
    val gyroscopeZ: Float = 0f,
)

enum class MotionAction {
    JUMP_UP,
    JUMP_LEFT,
    JUMP_RIGHT,
    SQUAT,
    RUNNING,
}

enum class ActionPhase {
    TRIGGER,
    START,
    UPDATE,
    STOP,
}

data class DetectedAction(
    val action: MotionAction,
    val phase: ActionPhase,
    val confidence: Float,
    val timestampNs: Long,
    val verticalAcceleration: Float,
    val lateralAcceleration: Float,
)

enum class MotionState {
    CALIBRATING,
    READY,
    JUMP,
    SQUAT,
    RUNNING,
}

data class RecognitionResult(
    val state: MotionState,
    val sample: MotionSample,
    val actions: List<DetectedAction>,
    val runningConfidence: Float,
    val jumpState: JumpDetector.State,
    val squatState: SquatDetector.State,
    val enabledActions: Set<MotionAction> = MotionAction.entries.toSet(),
)
