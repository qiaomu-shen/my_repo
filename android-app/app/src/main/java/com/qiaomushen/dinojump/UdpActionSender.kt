package com.qiaomushen.dinojump

import org.json.JSONObject
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class UdpActionSender : Closeable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val socket = DatagramSocket()
    private val sessionId = UUID.randomUUID().toString()

    fun sendAction(
        host: String,
        port: Int,
        sequence: Long,
        action: DetectedAction,
        onResult: (Result<Unit>) -> Unit,
    ) {
        executor.execute {
            val result = runCatching {
                require(host.isNotBlank()) { "Computer IP cannot be empty" }
                require(port in 1..65535) { "Port must be between 1 and 65535" }

                val payload = JSONObject()
                    .put("event", "ACTION")
                    .put("action", action.action.name)
                    .put("phase", action.phase.name)
                    .put("confidence", action.confidence.toDouble())
                    .put("session_id", sessionId)
                    .put("sequence", sequence)
                    .put("phone_time_ns", action.timestampNs)
                    .put("vertical_acceleration", action.verticalAcceleration.toDouble())
                    .put("lateral_acceleration", action.lateralAcceleration.toDouble())
                    .toString()
                    .toByteArray(Charsets.UTF_8)

                val packet = DatagramPacket(
                    payload,
                    payload.size,
                    InetAddress.getByName(host),
                    port,
                )
                socket.send(packet)
            }
            onResult(result)
        }
    }

    override fun close() {
        executor.shutdownNow()
        socket.close()
    }
}
