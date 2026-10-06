package com.cayana.backup.drive

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

data class MockRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

data class MockResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val bodyString: String = ""
)

class MockHttpServer : AutoCloseable {
    private val serverSocket = ServerSocket(0)
    val port: Int = serverSocket.localPort
    @Volatile private var isRunning = true
    private val handlers = CopyOnWriteArrayList<Pair<(String, String) -> Boolean, (MockRequest) -> MockResponse>>()

    init {
        Thread {
            while (isRunning) {
                try {
                    val socket = serverSocket.accept()
                    Thread { handleConnection(socket) }.apply { isDaemon = true; start() }
                } catch (_: Exception) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun on(matcher: (method: String, path: String) -> Boolean, handler: (MockRequest) -> MockResponse) {
        handlers.add(matcher to handler)
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 5000
                val input = s.getInputStream()
                val output = s.getOutputStream()

                val headerBytes = ByteArrayOutputStream()
                var prev1 = -1
                var prev2 = -1
                var prev3 = -1
                while (true) {
                    val b = input.read()
                    if (b == -1) break
                    headerBytes.write(b)
                    if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                        break
                    }
                    prev3 = prev2
                    prev2 = prev1
                    prev1 = b
                }

                val headerText = headerBytes.toString("ISO-8859-1")
                val lines = headerText.lines()
                if (lines.isEmpty() || lines[0].isBlank()) return
                val reqParts = lines[0].split(" ")
                val method = reqParts[0]
                val path = reqParts[1]

                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val k = line.substring(0, colon).trim().lowercase()
                        val v = line.substring(colon + 1).trim()
                        headers[k] = v
                        if (k == "content-length") {
                            contentLength = v.toIntOrNull() ?: 0
                        }
                    }
                }

                val bodyBytes = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var readBytes = 0
                    while (readBytes < contentLength) {
                        val r = input.read(buf, readBytes, contentLength - readBytes)
                        if (r == -1) break
                        readBytes += r
                    }
                    buf
                } else ByteArray(0)

                val request = MockRequest(method, path, headers, bodyBytes)
                val matchedHandler = handlers.firstOrNull { it.first(method, path) }?.second
                val response = matchedHandler?.invoke(request) ?: MockResponse(404, emptyMap(), "Not Found")

                val statusReason = when (response.statusCode) {
                    200 -> "OK"
                    201 -> "Created"
                    308 -> "Resume Incomplete"
                    400 -> "Bad Request"
                    401 -> "Unauthorized"
                    404 -> "Not Found"
                    500 -> "Internal Server Error"
                    503 -> "Service Unavailable"
                    else -> "Status"
                }

                val respBodyBytes = response.bodyString.toByteArray(Charsets.UTF_8)
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ${response.statusCode} $statusReason\r\n")
                response.headers.forEach { (k, v) ->
                    sb.append("$k: $v\r\n")
                }
                sb.append("Content-Length: ${respBodyBytes.size}\r\n")
                sb.append("Connection: close\r\n")
                sb.append("\r\n")

                output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                if (respBodyBytes.isNotEmpty()) {
                    output.write(respBodyBytes)
                }
                output.flush()
            }
        } catch (_: Exception) {}
    }

    override fun close() {
        isRunning = false
        try { serverSocket.close() } catch (_: Exception) {}
    }
}
