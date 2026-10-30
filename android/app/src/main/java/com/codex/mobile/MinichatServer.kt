package com.codex.mobile

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-contained, node-free HTTP server for the custom-endpoint chat UI.
 *
 * Serves the bundled index.html and proxies chat-completions requests to any
 * OpenAI-compatible endpoint (from MERCURY_ENDPOINT / MERCURY_API_KEY). This
 * replaces the previous Node.js server, which could not link on device due to
 * an old system libc++ (missing _ZNSt6__ndk113__hash_memoryEPKvm).
 */
class MinichatServer(private val context: Context) {

    companion object {
        private const val TAG = "MinichatServer"
        const val MINICHAT_PORT = 18925
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    data class Config(val endpoint: String, val apiKey: String, val model: String)

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            serverSocket = ServerSocket(MINICHAT_PORT, 64, InetAddress.getByName("127.0.0.1"))
            running.set(true)
            acceptThread = Thread { acceptLoop() }.apply {
                isDaemon = true
                name = "minichat-accept"
                start()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "bind failed on $MINICHAT_PORT", e)
            false
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = serverSocket?.accept() ?: break
                Thread { handle(socket) }.apply {
                    isDaemon = true
                    name = "minichat-conn"
                    start()
                }
            } catch (e: Exception) {
                if (running.get()) {
                    // transient accept error; keep serving
                } else break
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val head = readHead(input) ?: return
            val requestLine = head[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            for (i in 1 until head.size) {
                val idx = head[i].indexOf(':')
                if (idx > 0) {
                    headers[head[i].substring(0, idx).trim().lowercase()] =
                        head[i].substring(idx + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

            when {
                method == "OPTIONS" -> {
                    sendSimple(output, 204, null, "text/plain", cors = true)
                }
                method == "GET" && path == "/" -> {
                    val html = readAsset("minichat/index.html")
                    sendSimple(output, 200, html, "text/html; charset=utf-8", cors = true)
                }
                method == "GET" && path == "/v1/models" -> {
                    val cfg = loadConfig()
                    val json = "{\"models\":[{\"id\":\"${cfg.model}\",\"name\":\"${cfg.model}\"}]}"
                    sendSimple(output, 200, json, "application/json", cors = true)
                }
                method == "POST" && path == "/v1/chat/completions" -> {
                    val body = if (contentLength > 0) {
                        ByteArray(contentLength).also { readFully(input, it) }
                    } else ByteArray(0)
                    proxyChat(output, body)
                }
                else -> {
                    sendSimple(output, 404, "{\"error\":{\"message\":\"not found\"}}", "application/json", cors = true)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "connection error: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readHead(input: InputStream): List<String>? {
        val sb = StringBuilder()
        var prev = -1
        val buf = ByteArray(1)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return null
            val b = buf[0].toInt()
            sb.append(b.toChar())
            if (prev == 13 && b == 10 && sb.toString().endsWith("\r\n\r\n")) break
            prev = b
            if (sb.length > 65536) return null
        }
        val raw = sb.toString()
        val lines = raw.split("\r\n")
        val result = mutableListOf<String>()
        for (line in lines) {
            if (line.isNotEmpty()) result.add(line)
        }
        return result.ifEmpty { null }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) throw java.io.EOFException("unexpected EOF")
            offset += n
        }
    }

    private fun sendSimple(output: OutputStream, status: Int, body: String?, ctype: String, cors: Boolean) {
        val bodyBytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status)
        head.append(if (status == 200 || status == 204) " OK" else " ").append("\r\n")
        head.append("Content-Type: ").append(ctype).append("\r\n")
        head.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        if (cors) head.append("Access-Control-Allow-Origin: *\r\n")
        head.append("Connection: close\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.UTF_8))
        if (bodyBytes.isNotEmpty()) output.write(bodyBytes)
        output.flush()
    }

    private fun proxyChat(output: OutputStream, body: ByteArray) {
        val cfg = loadConfig()
        if (cfg.endpoint.isBlank() || cfg.apiKey.isBlank()) {
            sendSimple(
                output,
                400,
                "{\"error\":{\"message\":\"Custom endpoint not configured (set Base URL + API Key in app settings)\"}}",
                "application/json",
                cors = true,
            )
            return
        }
        try {
            val conn = URL(cfg.endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { os ->
                os.write(body)
                os.flush()
            }

            val status = conn.responseCode
            val ctype = conn.contentType ?: "application/json"
            val respStream = if (status >= 400) conn.errorStream else conn.inputStream

            val bodyBytes = if (respStream != null) {
                ByteArrayOutputStream().also { baos ->
                    respStream.use { inp ->
                        val chunk = ByteArray(8192)
                        var n: Int
                        while (inp.read(chunk).also { n = it } != -1) {
                            baos.write(chunk, 0, n)
                        }
                    }
                }.toByteArray()
            } else ByteArray(0)

            val head = StringBuilder()
            head.append("HTTP/1.1 ").append(status)
            head.append(if (status == 200) " OK" else " ").append("\r\n")
            head.append("Content-Type: ").append(ctype).append("\r\n")
            head.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            head.append("Access-Control-Allow-Origin: *\r\n")
            head.append("Connection: close\r\n")
            head.append("\r\n")
            output.write(head.toString().toByteArray(Charsets.UTF_8))
            if (bodyBytes.isNotEmpty()) output.write(bodyBytes)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "upstream error", e)
            sendSimple(
                output,
                502,
                "{\"error\":{\"message\":\"Upstream error: ${e.message?.replace("\"", "\\\"")}\"}}",
                "application/json",
                cors = true,
            )
        }
    }

    private fun loadConfig(): Config {
        val homeDir = try {
            BootstrapInstaller.getPaths(context).homeDir
        } catch (e: Exception) {
            ""
        }
        var endpoint = System.getenv("MERCURY_ENDPOINT") ?: ""
        var apiKey = System.getenv("MERCURY_API_KEY") ?: ""
        var model = System.getenv("MERCURY_MODEL") ?: "mercury-3"

        fun parseFile() {
            if (homeDir.isEmpty()) return
            val f = java.io.File(homeDir, ".mercury.env")
            if (!f.exists()) return
            try {
                for (raw in f.readLines()) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val idx = line.indexOf('=')
                    if (idx <= 0) continue
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    when (k) {
                        "MERCURY_ENDPOINT" -> if (endpoint.isEmpty()) endpoint = v
                        "MERCURY_API_KEY" -> if (apiKey.isEmpty()) apiKey = v
                        "MERCURY_MODEL" -> model = v
                    }
                }
            } catch (_: Exception) {
            }
        }
        parseFile()
        return Config(endpoint, apiKey, model)
    }

    private fun readAsset(name: String): String {
        return try {
            context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "asset read failed: $name", e)
            ""
        }
    }
}
