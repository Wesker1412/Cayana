package com.cayana.ask.remote

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Lightweight, zero-dependency Mock HTTP Server implemented via standard Java ServerSocket.
 * Avoids any restricted/internal JDK packages, running reliably in all Android/JVM test environments.
 */
class MockInferenceHttpServer {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false
    private var serverThread: Thread? = null

    val recordedRequests = CopyOnWriteArrayList<RecordedHttpRequest>()

    var responseStatusCode: Int = 200
    var responseBody: String = """{"requestId":"test","answer":"Default answer","evidenceState":"SUPPORTED","citedMemoryIds":[]}"""
    var responseHeaders: MutableMap<String, String> = mutableMapOf("Content-Type" to "application/json; charset=utf-8")
    var simulatedDelayMs: Long = 0L

    val port: Int get() = serverSocket?.localPort ?: 0
    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun start() {
        val ss = ServerSocket(0)
        serverSocket = ss
        running = true
        serverThread = thread(isDaemon = true, name = "MockInferenceHttpServer") {
            while (running) {
                try {
                    val clientSocket = ss.accept() ?: break
                    thread(isDaemon = true) {
                        handleClient(clientSocket)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"

                val headers = mutableMapOf<String, MutableList<String>>()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon != -1) {
                        val k = line.substring(0, colon).trim().lowercase()
                        val v = line.substring(colon + 1).trim()
                        headers.getOrPut(k) { mutableListOf() }.add(v)
                        if (k == "content-length") {
                            contentLength = v.toIntOrNull() ?: 0
                        }
                    }
                }

                val bodyBytes = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = reader.read(buf, read, contentLength - read)
                        if (r == -1) break
                        read += r
                    }
                    String(buf, 0, read).toByteArray(Charsets.UTF_8)
                } else {
                    ByteArray(0)
                }

                recordedRequests.add(
                    RecordedHttpRequest(
                        method = method,
                        path = path,
                        headers = headers,
                        bodyBytes = bodyBytes,
                        bodyString = bodyBytes.toString(Charsets.UTF_8)
                    )
                )

                if (simulatedDelayMs > 0) {
                    Thread.sleep(simulatedDelayMs)
                }

                val output: OutputStream = s.getOutputStream()
                val statusText = when (responseStatusCode) {
                    200 -> "OK"
                    400 -> "Bad Request"
                    401 -> "Unauthorized"
                    413 -> "Payload Too Large"
                    429 -> "Too Many Requests"
                    500 -> "Internal Server Error"
                    502 -> "Bad Gateway"
                    302 -> "Found"
                    else -> "Status"
                }

                val respBytes = responseBody.toByteArray(Charsets.UTF_8)
                val sb = StringBuilder()
                sb.append("HTTP/1.1 $responseStatusCode $statusText\r\n")
                responseHeaders.forEach { (k, v) ->
                    sb.append("$k: $v\r\n")
                }
                sb.append("Content-Length: ${respBytes.size}\r\n")
                sb.append("Connection: close\r\n")
                sb.append("\r\n")

                output.write(sb.toString().toByteArray(Charsets.UTF_8))
                output.write(respBytes)
                output.flush()
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    fun reset() {
        recordedRequests.clear()
        responseStatusCode = 200
        responseBody = """{"requestId":"test","answer":"Default answer","evidenceState":"SUPPORTED","citedMemoryIds":[]}"""
        responseHeaders = mutableMapOf("Content-Type" to "application/json; charset=utf-8")
        simulatedDelayMs = 0L
    }

    data class RecordedHttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val bodyBytes: ByteArray,
        val bodyString: String
    )
}
