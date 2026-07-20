package com.qiaomushen.dinojump

import org.json.JSONObject
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class UdpJumpSender : Closeable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val socket = DatagramSocket()

    fun sendJump(
        host: String,
        port: Int,
        sequence: Long,
        phoneTimeNs: Long,
        verticalAcceleration: Float?,
        onResult: (Result<Unit>) -> Unit,
    ) {
        executor.execute {
            val result = runCatching {
                require(host.isNotBlank()) { "Computer IP cannot be empty" }
                require(port in 1..65535) { "Port must be between 1 and 65535" }

                val payload = JSONObject()
                    .put("event", "JUMP")
                    .put("sequence", sequence)
                    .put("phone_time_ns", phoneTimeNs)
                    .put("vertical_acceleration", verticalAcceleration ?: JSONObject.NULL)
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
