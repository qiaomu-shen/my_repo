package com.qiaomushen.dinojump

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor
    private lateinit var rotationVector: Sensor
    private var gyroscope: Sensor? = null

    private val frameTransformer = SensorFrameTransformer()
    private val recognizer = MotionRecognizer()
    private val sender = UdpActionSender()
    private val recorder = MotionCsvRecorder()

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var thresholdInput: EditText
    private lateinit var runningCheckBox: CheckBox
    private lateinit var lateralJumpCheckBox: CheckBox
    private lateinit var verticalJumpCheckBox: CheckBox
    private lateinit var squatCheckBox: CheckBox
    private lateinit var startButton: Button
    private lateinit var testButton: Button
    private lateinit var stateText: TextView
    private lateinit var actionText: TextView
    private lateinit var accelerationText: TextView
    private lateinit var countText: TextView
    private lateinit var networkText: TextView
    private lateinit var recordingText: TextView

    private var detecting = false
    private var sequence = 0L
    private var sentCount = 0
    private var lastUiUpdateNs = 0L
    private var lastActionLabel = "none"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        thresholdInput = findViewById(R.id.thresholdInput)
        runningCheckBox = findViewById(R.id.runningCheckBox)
        lateralJumpCheckBox = findViewById(R.id.lateralJumpCheckBox)
        verticalJumpCheckBox = findViewById(R.id.verticalJumpCheckBox)
        squatCheckBox = findViewById(R.id.squatCheckBox)
        startButton = findViewById(R.id.startButton)
        testButton = findViewById(R.id.testButton)
        stateText = findViewById(R.id.stateText)
        actionText = findViewById(R.id.actionText)
        accelerationText = findViewById(R.id.accelerationText)
        countText = findViewById(R.id.countText)
        networkText = findViewById(R.id.networkText)
        recordingText = findViewById(R.id.recordingText)

        val preferences = getSharedPreferences("dino_jump", MODE_PRIVATE)
        hostInput.setText(preferences.getString("host", "192.168.1.100"))
        portInput.setText(preferences.getInt("port", 5005).toString())
        thresholdInput.setText(preferences.getFloat("threshold", 2.8f).toString())
        runningCheckBox.isChecked = preferences.getBoolean("action_running", true)
        lateralJumpCheckBox.isChecked = preferences.getBoolean("action_lateral_jump", true)
        verticalJumpCheckBox.isChecked = preferences.getBoolean("action_vertical_jump", true)
        squatCheckBox.isChecked = preferences.getBoolean("action_squat", true)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = requireNotNull(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)) {
            "This phone does not provide an accelerometer"
        }
        rotationVector = requireNotNull(
            sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
        ) { "This phone does not provide a rotation vector sensor" }
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        startButton.setOnClickListener {
            if (detecting) stopDetection() else startDetection()
        }
        testButton.setOnClickListener {
            sendAction(
                DetectedAction(
                    action = MotionAction.JUMP_UP,
                    phase = ActionPhase.TRIGGER,
                    confidence = 1f,
                    timestampNs = System.nanoTime(),
                    verticalAcceleration = 0f,
                    lateralAcceleration = 0f,
                ),
            )
        }

        renderState(MotionState.READY, 0f, 0f, getString(R.string.no_action))
    }

    private fun startDetection() {
        val threshold = thresholdInput.text.toString().toFloatOrNull()
        if (threshold == null || threshold !in 0.5f..15f) {
            thresholdInput.error = "Use a value from 0.5 to 15"
            return
        }
        val port = portInput.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) {
            portInput.error = "Use a port from 1 to 65535"
            return
        }
        if (hostInput.text.toString().isBlank()) {
            hostInput.error = "Enter the computer IP"
            return
        }
        val enabledActions = selectedActions()
        if (enabledActions.isEmpty()) {
            Toast.makeText(this, R.string.select_one_action, Toast.LENGTH_SHORT).show()
            return
        }

        recognizer.setTakeoffThreshold(threshold)
        recognizer.setEnabledActions(enabledActions)
        recognizer.reset()
        frameTransformer.reset()
        lastUiUpdateNs = 0L
        lastActionLabel = getString(R.string.no_action)
        detecting = true
        startButton.text = getString(R.string.stop_detection)
        hostInput.isEnabled = false
        portInput.isEnabled = false
        thresholdInput.isEnabled = false
        setActionSpaceEnabled(false)
        networkText.text = getString(R.string.network_ready)

        getSharedPreferences("dino_jump", MODE_PRIVATE)
            .edit()
            .putString("host", hostInput.text.toString().trim())
            .putInt("port", port)
            .putFloat("threshold", threshold)
            .putBoolean("action_running", runningCheckBox.isChecked)
            .putBoolean("action_lateral_jump", lateralJumpCheckBox.isChecked)
            .putBoolean("action_vertical_jump", verticalJumpCheckBox.isChecked)
            .putBoolean("action_squat", squatCheckBox.isChecked)
            .apply()

        val recordingDirectory = getExternalFilesDir("recordings")
            ?: File(filesDir, "recordings")
        val recordingFile = recorder.start(recordingDirectory)
        recordingText.text = getString(R.string.recording_path, recordingFile.absolutePath)

        registerSensor(accelerometer)
        registerSensor(rotationVector)
        gyroscope?.let(::registerSensor)
    }

    private fun registerSensor(sensor: Sensor) {
        sensorManager.registerListener(this, sensor, 10_000)
    }

    private fun stopDetection() {
        if (!detecting) return
        recognizer.stop(System.nanoTime()).forEach(::sendAction)
        detecting = false
        sensorManager.unregisterListener(this)
        recorder.stop()
        startButton.text = getString(R.string.start_detection)
        hostInput.isEnabled = true
        portInput.isEnabled = true
        thresholdInput.isEnabled = true
        setActionSpaceEnabled(true)
        stateText.text = getString(R.string.state_stopped)
    }

    private fun selectedActions(): Set<MotionAction> = buildSet {
        if (runningCheckBox.isChecked) add(MotionAction.RUNNING)
        if (lateralJumpCheckBox.isChecked) {
            add(MotionAction.JUMP_LEFT)
            add(MotionAction.JUMP_RIGHT)
        }
        if (verticalJumpCheckBox.isChecked) add(MotionAction.JUMP_UP)
        if (squatCheckBox.isChecked) add(MotionAction.SQUAT)
    }

    private fun setActionSpaceEnabled(enabled: Boolean) {
        runningCheckBox.isEnabled = enabled
        lateralJumpCheckBox.isEnabled = enabled
        verticalJumpCheckBox.isEnabled = enabled
        squatCheckBox.isEnabled = enabled
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!detecting) return

        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> frameTransformer.updateRotationVector(event.values)

            Sensor.TYPE_GYROSCOPE -> frameTransformer.updateGyroscope(
                event.values[0],
                event.values[1],
                event.values[2],
            )

            Sensor.TYPE_ACCELEROMETER -> {
                val sample = frameTransformer.transformAccelerometer(
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    timestampNs = event.timestamp,
                ) ?: return
                val result = recognizer.update(sample)
                recorder.record(result)
                result.actions.forEach(::sendAction)

                if (event.timestamp - lastUiUpdateNs >= 50_000_000L || result.actions.isNotEmpty()) {
                    lastUiUpdateNs = event.timestamp
                    result.actions.lastOrNull()?.let {
                        lastActionLabel =
                            "${it.action} ${it.phase} (${String.format(Locale.US, "%.2f", it.confidence)})"
                    }
                    renderState(
                        result.state,
                        result.sample.verticalAcceleration,
                        result.sample.lateralAcceleration,
                        lastActionLabel,
                    )
                }
            }
        }
    }

    private fun sendAction(action: DetectedAction) {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull() ?: 5005
        val currentSequence = sequence++

        sender.sendAction(
            host = host,
            port = port,
            sequence = currentSequence,
            action = action,
        ) { result ->
            runOnUiThread {
                result.onSuccess {
                    sentCount += 1
                    countText.text = getString(R.string.sent_count, sentCount)
                    networkText.text = getString(
                        R.string.last_send_ok,
                        currentSequence,
                        action.action.name,
                        action.phase.name,
                    )
                }.onFailure { error ->
                    networkText.text = getString(
                        R.string.last_send_failed,
                        error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private fun renderState(
        state: MotionState,
        verticalAcceleration: Float,
        lateralAcceleration: Float,
        actionLabel: String,
    ) {
        stateText.text = when (state) {
            MotionState.CALIBRATING -> getString(R.string.state_calibrating)
            MotionState.READY -> getString(R.string.state_ready)
            MotionState.JUMP -> getString(R.string.state_jump)
            MotionState.SQUAT -> getString(R.string.state_squat)
            MotionState.RUNNING -> getString(R.string.state_running)
        }
        actionText.text = getString(R.string.last_action, actionLabel)
        accelerationText.text = getString(
            R.string.motion_acceleration,
            String.format(Locale.US, "%.2f", verticalAcceleration),
            String.format(Locale.US, "%.2f", lateralAcceleration),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onPause() {
        super.onPause()
        if (detecting) stopDetection()
    }

    override fun onDestroy() {
        recorder.close()
        sender.close()
        super.onDestroy()
    }
}
