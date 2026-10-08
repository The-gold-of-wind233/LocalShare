package com.localshare.app

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStream

/**
 * 与电脑通信的最小 HTTP 客户端。
 *
 * 三个接口：
 *  · ping   —— 测试连通性
 *  · push   —— 把手机剪贴板内容推到电脑
 *  · stream —— SSE 长连接，实时接收电脑剪贴板变化
 *
 * 全部为阻塞式调用，请在后台线程使用。
 */
object Api {

    private fun open(url: String, method: String, timeoutMs: Int = 5000): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    /** 测试电脑是否可达。 */
    fun ping(baseUrl: String, codeQuery: String): Boolean = try {
        val conn = open("$baseUrl/api/ping$codeQuery", "GET", 3000)
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    /** 推送文本到电脑剪贴板。 */
    fun push(baseUrl: String, codeQuery: String, text: String): Boolean = try {
        val conn = open("$baseUrl/api/clipboard$codeQuery", "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

        val body = JSONObject().put("text", text).toString()
        val os: OutputStream = conn.outputStream
        os.write(body.toByteArray(Charsets.UTF_8))
        os.flush()
        os.close()

        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    /**
     * 上传文件到电脑（裸流方式，比 multipart 省一半流量）。
     *
     * @param fileName 原始文件名，服务端会做清洗（防路径穿越）
     * @param onProgress 已上传字节数回调，可为 null
     */
    fun upload(
        baseUrl: String,
        codeQuery: String,
        fileName: String,
        mime: String,
        file: java.io.File,
        onProgress: ((Long) -> Unit)? = null
    ): Boolean = try {
        val conn = open("$baseUrl/api/upload$codeQuery", "POST", 30_000)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", mime.ifBlank { "application/octet-stream" })
        conn.setRequestProperty(
            "X-File-Name",
            java.net.URLEncoder.encode(fileName, "UTF-8")
        )
        conn.setRequestProperty("Connection", "close")
        // 用 Long 重载：避免大文件（>2GB）toInt 溢出，也避免全量缓冲进内存
        conn.setFixedLengthStreamingMode(file.length())

        var sent = 0L
        file.inputStream().use { input ->
            conn.outputStream.use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    sent += n
                    onProgress?.invoke(sent)
                }
                out.flush()
            }
        }

        val ok = conn.responseCode in 200..299
        if (!ok) {
            val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { null }
            android.util.Log.w("LocalShare", "upload failed: ${conn.responseCode} $err")
        }
        conn.disconnect()
        ok
    } catch (e: Exception) {
        android.util.Log.w("LocalShare", "upload error", e)
        false
    }

    /** 已接收文件列表（电脑端保存的文件）。 */
    fun received(baseUrl: String, codeQuery: String): List<ReceivedFile> {
        val conn = open("$baseUrl/api/received$codeQuery", "GET", 5000)
        return try {
            if (conn.responseCode != 200) return emptyList()
            val text = conn.inputStream.bufferedReader().readText()
            val arr = org.json.JSONObject(text).optJSONArray("files") ?: return emptyList()
            val out = ArrayList<ReceivedFile>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    ReceivedFile(
                        id = o.optString("id", ""),
                        name = o.optString("name", ""),
                        size = o.optLong("size", 0),
                        time = o.optString("time", "")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    data class ReceivedFile(
        val id: String,
        val name: String,
        val size: Long,
        val time: String
    )

    /**
     * SSE 长连接：阻塞读取，每收到一条事件回调 [onText]。
     * 连接断开或异常时返回（调用方负责重连）。
     *
     * @param stop 外部取消信号，置为 true 后循环会尽快退出
     */
    fun stream(
        baseUrl: String,
        codeQuery: String,
        stop: () -> Boolean,
        onText: (String) -> Unit
    ) {
        var conn: HttpURLConnection? = null
        try {
            conn = open("$baseUrl/api/clip/stream$codeQuery", "GET")
            conn.readTimeout = 0          // 0 = 不超时，长连接必需
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.connect()

            if (conn.responseCode != 200) return

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            while (!stop()) {
                val line = reader.readLine() ?: break       // 服务端关闭 → null
                if (!line.startsWith("data:")) continue     // 忽略 event: / 心跳 : ping / 空行

                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue

                val text = try {
                    JSONObject(payload).optString("text", "")
                } catch (e: Exception) {
                    continue
                }
                if (text.isNotEmpty()) onText(text)
            }
        } catch (e: Exception) {
            // 断网 / 电脑退出 / 线程被打断：返回后由调用方按退避重连
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
