package com.localshare.app

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** 局域网内的一台运行 LocalShare 的电脑。 */
data class PcInfo(
    val host: String,
    val ip: String,
    val port: Int,
    val requireCode: Boolean
) {
    override fun toString(): String = if (host.isBlank()) "$ip:$port" else "$host  ($ip:$port)"
}

/**
 * UDP 广播发现：不需要扫码、不需要手输 IP。
 *
 * 电脑端的 DiscoveryService 监听 41523/UDP，收到 "LOCASHARE_DISCOVER" 后
 * 单播回复一段 JSON。这里负责发出探测并收集回复。
 *
 * 必须在后台线程调用（网络操作不能在主线程）。
 */
object Discovery {
    const val PORT = 41523
    private const val MAGIC = "LOCASHARE_DISCOVER"

    /**
     * 广播探测并收集回复。
     * @param timeoutMs 等待回复的时间
     */
    fun discover(timeoutMs: Int = 2500): List<PcInfo> {
        val found = LinkedHashMap<String, PcInfo>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = timeoutMs

            // 广播探测包
            val out = MAGIC.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(out, out.size, InetAddress.getByName("255.255.255.255"), PORT))

            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val info = parse(text) ?: continue
                found[info.ip] = info
            }
        } catch (e: Exception) {
            // 无网络 / 权限问题：返回空列表，由调用方提示
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
        return found.values.toList()
    }

    private fun parse(json: String): PcInfo? = try {
        val o = JSONObject(json)
        val ip = o.optString("ip", "")
        val port = o.optInt("port", 0)
        if (ip.isBlank() || port <= 0) null
        else PcInfo(o.optString("host", ""), ip, port, o.optBoolean("requireCode", false))
    } catch (e: Exception) {
        null
    }
}
