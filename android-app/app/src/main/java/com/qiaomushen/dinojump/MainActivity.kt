package com.qiaomushen.dinojump

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor
    private lateinit var detector: JumpDetector
    private val sender = UdpJumpSender()

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var thresholdInput: EditText
    private lateinit var startButton: Button
    private lateinit var testButton: Button
    private lateinit var stateText: TextView
    private lateinit var accelerationText: TextView
    private lateinit var countText: TextView
    private lateinit var networkText: TextView

    private var detecting = false
    private var sequence = 0L
    private var sentCount = 0
    private var lastUiUpdateNs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        thresholdInput = findViewById(R.id.thresholdInput)
        startButton = findViewById(R.id.startButton)
        testButton = findViewById(R.id.testButton)
        stateText = findViewById(R.id.stateText)
        accelerationText = findViewById(R.id.accelerationText)
        countText = findViewById(R.id.countText)
        networkText = findViewById(R.id.networkText)

        val preferences = getSharedPreferences("dino_jump", MODE_PRIVATE)
        hostInput.setText(preferences.getString("host", "192.168.1.100"))
        portInput.setText(preferences.getInt("port", 5005).toString())
        thresholdInput.setText(preferences.getFloat("threshold", 2.8f).toString())

        detector = JumpDetector()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = requireNotNull(
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
        ) { "This phone does not provide an accelerometer" }

        startButton.setOnClickListener {
            if (detecting) stopDetection() else startDetection()
        }
        testButton.setOnClickListener {
            sendJump(System.nanoTime(), null)
        }

        renderState(JumpDetector.State.READY, 0f)
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

        detector.setThreshold(threshold)
        detector.reset()
        detecting = true
        startButton.text = getString(R.string.stop_detection)
        hostInput.isEnabled = false
        portInput.isEnabled = false
        thresholdInput.isEnabled = false
        networkText.text = getString(R.string.network_ready)

        getSharedPreferences("dino_jump", MODE_PRIVATE)
            .edit()
            .putString("host", hostInput.text.toString().trim())
            .putInt("port", port)
            .putFloat("threshold", threshold)
            .apply()

        sensorManager.registerListener(
            this,
            accelerometer,
            10_000,
        )
    }

    private fun stopDetection() {
        detecting = false
        sensorManager.unregisterListener(this)
        startButton.text = getString(R.string.start_detection)
        hostInput.isEnabled = true
        portInput.isEnabled = true
        thresholdInput.isEnabled = true
        stateText.text = getString(R.string.state_stopped)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!detecting || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val result = detector.update(
            accelerationX = event.values[0],
            accelerationY = event.values[1],
            accelerationZ = event.values[2],
            timestampNs = event.timestamp,
        )

        if (result.triggered) {
            sendJump(event.timestamp, result.verticalAcceleration)
        }

        if (event.timestamp - lastUiUpdateNs >= 50_000_000L || result.triggered) {
            lastUiUpdateNs = event.timestamp
            renderState(result.state, result.verticalAcceleration)
        }
    }

    private fun sendJump(phoneTimeNs: Long, verticalAcceleration: Float?) {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull() ?: 5005
        val currentSequence = sequence++

        sender.sendJump(
            host = host,
            port = port,
            sequence = currentSequence,
            phoneTimeNs = phoneTimeNs,
            verticalAcceleration = verticalAcceleration,
        ) { result ->
            runOnUiThread {
                result.onSuccess {
                    sentCount += 1
                    countText.text = getString(R.string.sent_count, sentCount)
                    networkText.text = getString(R.string.last_send_ok, currentSequence)
                }.onFailure { error ->
                    networkText.text = getString(
                        R.string.last_send_failed,
                        error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private fun renderState(state: JumpDetector.State, verticalAcceleration: Float) {
        stateText.text = when (state) {
            JumpDetector.State.CALIBRATING -> getString(R.string.state_calibrating)
            JumpDetector.State.READY -> getString(R.string.state_ready)
            JumpDetector.State.COOLDOWN -> getString(R.string.state_cooldown)
        }
        accelerationText.text = getString(
            R.string.vertical_acceleration,
            String.format(Locale.US, "%.2f", verticalAcceleration),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onPause() {
        super.onPause()
        if (detecting) stopDetection()
    }

    override fun onDestroy() {
        sender.close()
        super.onDestroy()
    }
}
