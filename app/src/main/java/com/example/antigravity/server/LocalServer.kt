package com.example.antigravity.server

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.Executors

class LocalServer(private val context: Context) {
    companion object {
        private const val TAG = "LocalServer"
    }

    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()
    @Volatile private var isRunning = false

    val port: Int
        get() = serverSocket?.localPort ?: 0

    val baseUrl: String
        get() = "http://127.0.0.1:$port"

    val projectsDir: File by lazy {
        val ext = Environment.getExternalStorageDirectory()
        val dir = File(ext, "Projects")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Log.i(TAG, "LocalServer started on port $port")

        threadPool.execute {
            while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                try {
                    val socket = serverSocket!!.accept()
                    threadPool.execute { handleClient(socket) }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error accepting connection", e)
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val pathAndQuery = parts[1]
                val path = pathAndQuery.substringBefore("?")
                Log.i(TAG, "HTTP REQ: $method $path")

                // Read headers
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val headerParts = line.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        headers[headerParts[0].trim().lowercase()] = headerParts[1].trim()
                    }
                }

                if (method.equals("OPTIONS", ignoreCase = true)) {
                    sendResponse(output, 200, "OK", "text/plain", ByteArray(0), true)
                    return
                }

                // Read body for POST requests
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBytes = if (contentLength > 0) {
                    val bytes = ByteArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val count = input.read(bytes, totalRead, contentLength - totalRead)
                        if (count < 0) break
                        totalRead += count
                    }
                    bytes
                } else ByteArray(0)

                // Handle RPC requests
                if (path.startsWith("/exa.language_server_pb.LanguageServerService/")) {
                    val rpcMethod = path.substringAfterLast("/")
                    val clientContentType = headers["content-type"] ?: ""
                    handleRpcMethod(output, rpcMethod, bodyBytes, clientContentType)
                    return
                }

                // Route static assets fallback
                if (method.equals("GET", ignoreCase = true)) {
                    val assetSubPath = if (path == "/" || path == "/index.html") "web/index.html" else "web" + path
                    if (assetExists(assetSubPath)) {
                        serveAsset(output, assetSubPath, guessMimeType(assetSubPath))
                        return
                    }
                    if (!path.contains(".")) {
                        serveAsset(output, "web/index.html", "text/html; charset=utf-8")
                        return
                    }
                }

                sendResponse(output, 404, "Not Found", "text/plain", "Not Found".toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        }
    }

    private fun handleRpcMethod(output: OutputStream, method: String, bodyBytes: ByteArray, clientContentType: String) {
        try {
            Log.i(TAG, "RPC CALL: $method (contentType=$clientContentType, bodyLen=${bodyBytes.size})")
            // Extract JSON safely regardless of Connect envelope prefix
            val reqJson = parseJsonFromBytes(bodyBytes)
            val respJson = JSONObject()

            when (method) {
                "GetServerConfiguration" -> {
                    val config = JSONObject().apply {
                        put("sidecars", JSONObject().put("enabled", true))
                        put("standalone", true)
                        put("appDataDir", "antigravity")
                        put("antigravityHub", true)
                        put("maxNumTrackedWorkspaces", 10)
                        put("subclientType", "hub")
                        put("configDir", "config")
                    }
                    respJson.put("config", config)
                }

                "GetLocalUserInfo" -> {
                    respJson.put("username", "mobile_developer")
                    respJson.put("homeDirUri", "file://${Environment.getExternalStorageDirectory().absolutePath}")
                }

                "GetMendelFlags" -> {
                    respJson.put("experimentConfig", JSONObject().put("experiments", JSONArray()))
                    respJson.put("webrtcMeshReady", true)
                }

                "JetboxSubscribeToState", "JetboxSubscribeToSummaries" -> {
                    // Empty state response
                }

                "SubscribeToSidecars" -> {
                    respJson.put("sidecars", JSONArray())
                }

                "GetUserStatus" -> {
                    val userStatus = JSONObject().apply {
                        put("name", "Mobile Developer")
                        put("email", "developer@antigravity.mobile")
                        put("planStatus", JSONObject().apply {
                            put("planInfo", JSONObject().apply {
                                put("teamsTier", "TEAMS_TIER_PRO")
                                put("planName", "Pro")
                                put("cascadeWebSearchEnabled", true)
                                put("cascadeCanAutoRunCommands", true)
                                put("canGenerateCommitMessages", true)
                                put("canAllowCascadeInBackground", true)
                                put("browserEnabled", true)
                            })
                            put("availablePromptCredits", 1000)
                            put("availableFlowCredits", 500)
                        })
                        put("cascadeModelConfigData", JSONObject().apply {
                            val configs = JSONArray().apply {
                                put(JSONObject().apply {
                                    put("label", "Gemini 2.5 Flash")
                                    put("modelId", "gemini-2.5-flash")
                                    put("isRecommended", true)
                                    put("modelOrAlias", JSONObject().put("model", "MODEL_CHAT_20706"))
                                    put("quotaInfo", JSONObject().put("remainingFraction", 1.0))
                                })
                            }
                            put("clientModelConfigs", configs)
                        })
                    }
                    respJson.put("userStatus", userStatus)
                }

                "GetStatus" -> {
                    // Empty 200
                }

                "Heartbeat" -> {
                    respJson.put("lastExtensionHeartbeat", Instant.now().toString())
                }

                "HasAuthToken" -> {
                    respJson.put("hasToken", true)
                }

                "GetAuthStatus" -> {
                    val auth = JSONObject().apply {
                        put("isLoggedIn", true)
                        put("email", "developer@antigravity.mobile")
                        put("displayName", "Mobile Developer")
                    }
                    respJson.put("authStatus", auth)
                }

                "GetDefaultProjectDir" -> {
                    respJson.put("defaultProjectDirUri", "file://${projectsDir.absolutePath}")
                }

                "GetWorkingDirectories" -> {
                    // Empty 200
                }

                "GetUserSettings" -> {
                    respJson.put("userSettings", JSONObject().apply {
                        put("themeMode", "THEME_MODE_DARK")
                    })
                }

                "GetContribVariants" -> {
                    respJson.put("variants", JSONArray().apply {
                        put("control")
                        put("native_tools")
                    })
                }

                "ListProjects" -> {
                    respJson.put("projectIds", JSONArray())
                }

                "ReadProjects" -> {
                    respJson.put("projects", JSONArray())
                    respJson.put("notFoundIds", JSONArray())
                }

                "ReadProject" -> {
                    respJson.put("project", JSONObject())
                }

                "ListLicenses" -> {
                    respJson.put("licenses", JSONArray())
                }

                "ListTriggers" -> {
                    respJson.put("triggers", JSONArray())
                }

                "ListTriggerOptions" -> {
                    respJson.put("options", JSONArray())
                }

                "ProjectUpdatesStream" -> {
                    respJson.put("updates", JSONArray())
                }

                "GetRepoInfos" -> {
                    respJson.put("repoInfos", JSONArray())
                }

                "GetDebugDiagnostics" -> {
                    respJson.put("diagnostics", JSONArray())
                }

                "GetGrantedScopes" -> {
                    respJson.put("scopes", JSONArray().apply {
                        put("email")
                        put("profile")
                        put("openid")
                    })
                }

                "GetUpdateStatus" -> {
                    respJson.put("status", JSONObject().apply {
                        put("currentVersion", "2.25.0")
                        put("latestVersion", "2.25.0")
                    })
                }

                "StatUri" -> {
                    respJson.put("exists", false)
                }

                "GetAvailableModels" -> {
                    val models = JSONObject().apply {
                        val m = JSONObject().apply {
                            put("maxTokens", 16384)
                            put("quotaInfo", JSONObject().put("remainingFraction", 1))
                            put("model", "MODEL_CHAT_20706")
                            put("apiProvider", "API_PROVIDER_INTERNAL")
                            put("modelProvider", "MODEL_PROVIDER_GOOGLE")
                            put("supportsCumulativeContext", true)
                            put("tabJumpPrintLineRange", true)
                            put("supportsEstimateTokenCounter", true)
                            put("isInternal", false)
                        }
                        put("chat_20706", m)
                    }
                    respJson.put("response", JSONObject().put("models", models))
                }

                "GetAllCascadeTrajectories" -> {
                    respJson.put("trajectorySummaries", JSONObject())
                }

                "ReadFile" -> {
                    val uri = reqJson.optString("uri", "")
                    val file = uriToFile(uri)
                    if (file != null && file.exists() && file.isFile) {
                        val bytes = file.readBytes()
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        respJson.put("content", b64)
                        respJson.put("language", guessLanguage(file.name))
                    } else {
                        respJson.put("content", "")
                        respJson.put("language", "LANGUAGE_UNSPECIFIED")
                    }
                }

                "WriteFile" -> {
                    val uri = reqJson.optString("uri", "")
                    val contentB64 = reqJson.optString("content", "")
                    val file = uriToFile(uri)
                    if (file != null) {
                        file.parentFile?.mkdirs()
                        val bytes = Base64.decode(contentB64, Base64.NO_WRAP)
                        file.writeBytes(bytes)
                    }
                }

                "DeleteFileOrDirectory" -> {
                    val uri = reqJson.optString("uri", "")
                    val file = uriToFile(uri)
                    file?.deleteRecursively()
                }

                "RunCommand" -> {
                    val command = reqJson.optString("command", "")
                    val workingDirUri = reqJson.optString("workingDirectoryUri", "")
                    val workDir = uriToFile(workingDirUri) ?: projectsDir

                    val result = executeShell(command, workDir)
                    respJson.put("output", result.output)
                    respJson.put("exitCode", result.exitCode)
                }

                else -> {
                    Log.i(TAG, "Unhandled RPC method: $method -> returning empty JSONObject")
                }
            }

            val rawJsonBytes = respJson.toString().toByteArray(StandardCharsets.UTF_8)
            Log.i(TAG, "RPC RESP: $method -> ${respJson.toString().take(100)}")

            when {
                clientContentType.contains("grpc-web") -> {
                    val framed = frameGrpcWeb(rawJsonBytes)
                    sendResponse(output, 200, "OK", clientContentType, framed, true, isGrpc = true)
                }
                clientContentType.contains("connect") -> {
                    val framed = frameConnect(rawJsonBytes)
                    sendResponse(output, 200, "OK", clientContentType, framed, true)
                }
                else -> {
                    sendResponse(output, 200, "OK", "application/json", rawJsonBytes, true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling RPC $method", e)
            val errJson = JSONObject().apply {
                put("code", "internal")
                put("message", e.message ?: "Internal error")
            }
            val errBytes = errJson.toString().toByteArray(StandardCharsets.UTF_8)
            if (clientContentType.contains("grpc-web")) {
                val framed = frameGrpcWeb(errBytes)
                sendResponse(output, 200, "OK", clientContentType, framed, true, isGrpc = true)
            } else {
                sendResponse(output, 500, "Internal Server Error", "application/json", errBytes, true)
            }
        }
    }

    private fun frameGrpcWeb(jsonBytes: ByteArray): ByteArray {
        val len = jsonBytes.size
        val dataFrame = ByteArray(5 + len)
        dataFrame[0] = 0 // Flags: 0 = data
        dataFrame[1] = ((len shr 24) and 0xFF).toByte()
        dataFrame[2] = ((len shr 16) and 0xFF).toByte()
        dataFrame[3] = ((len shr 8) and 0xFF).toByte()
        dataFrame[4] = (len and 0xFF).toByte()
        System.arraycopy(jsonBytes, 0, dataFrame, 5, len)

        val trailer = "grpc-status: 0\r\ngrpc-message: OK\r\n".toByteArray(StandardCharsets.UTF_8)
        val tLen = trailer.size
        val trailerFrame = ByteArray(5 + tLen)
        trailerFrame[0] = 0x80.toByte() // Flags: 0x80 = 128 = Trailers
        trailerFrame[1] = ((tLen shr 24) and 0xFF).toByte()
        trailerFrame[2] = ((tLen shr 16) and 0xFF).toByte()
        trailerFrame[3] = ((tLen shr 8) and 0xFF).toByte()
        trailerFrame[4] = (tLen and 0xFF).toByte()
        System.arraycopy(trailer, 0, trailerFrame, 5, tLen)

        return dataFrame + trailerFrame
    }

    private fun parseJsonFromBytes(bytes: ByteArray): JSONObject {
        if (bytes.isEmpty()) return JSONObject()
        return try {
            val content = if (bytes.size > 5 && bytes[0] == 0.toByte()) {
                val len = ((bytes[1].toInt() and 0xFF) shl 24) or
                          ((bytes[2].toInt() and 0xFF) shl 16) or
                          ((bytes[3].toInt() and 0xFF) shl 8) or
                          (bytes[4].toInt() and 0xFF)
                if (len in 1..(bytes.size - 5)) {
                    String(bytes, 5, len, StandardCharsets.UTF_8)
                } else {
                    String(bytes, StandardCharsets.UTF_8)
                }
            } else {
                String(bytes, StandardCharsets.UTF_8)
            }.trim()

            if (content.startsWith("{")) JSONObject(content) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun frameConnect(jsonBytes: ByteArray): ByteArray {
        val len = jsonBytes.size
        val dataFrame = ByteArray(5 + len)
        dataFrame[0] = 0 // Flags: 0 = data
        dataFrame[1] = ((len shr 24) and 0xFF).toByte()
        dataFrame[2] = ((len shr 16) and 0xFF).toByte()
        dataFrame[3] = ((len shr 8) and 0xFF).toByte()
        dataFrame[4] = (len and 0xFF).toByte()
        System.arraycopy(jsonBytes, 0, dataFrame, 5, len)

        val trailer = "{}".toByteArray(StandardCharsets.UTF_8)
        val tLen = trailer.size
        val trailerFrame = ByteArray(5 + tLen)
        trailerFrame[0] = 2 // Flags: 2 = End of Stream (trailers)
        trailerFrame[1] = ((tLen shr 24) and 0xFF).toByte()
        trailerFrame[2] = ((tLen shr 16) and 0xFF).toByte()
        trailerFrame[3] = ((tLen shr 8) and 0xFF).toByte()
        trailerFrame[4] = (tLen and 0xFF).toByte()
        System.arraycopy(trailer, 0, trailerFrame, 5, tLen)

        return dataFrame + trailerFrame
    }

    data class CommandResult(val output: String, val exitCode: Int)

    private fun executeShell(command: String, workDir: File): CommandResult {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(if (workDir.exists()) workDir else projectsDir)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            val exitCode = process.waitFor()
            CommandResult(sb.toString(), exitCode)
        } catch (e: Exception) {
            CommandResult("Execution error: ${e.message}\n", -1)
        }
    }

    private fun uriToFile(uri: String): File? {
        if (uri.isEmpty()) return null
        return try {
            val path = if (uri.startsWith("file://")) {
                URLDecoder.decode(uri.substring(7), "UTF-8")
            } else {
                uri
            }
            File(path)
        } catch (_: Exception) {
            null
        }
    }

    private fun guessLanguage(fileName: String): String {
        return when (fileName.substringAfterLast(".").lowercase()) {
            "kt", "kts" -> "LANGUAGE_KOTLIN"
            "java" -> "LANGUAGE_JAVA"
            "py" -> "LANGUAGE_PYTHON"
            "js" -> "LANGUAGE_JAVASCRIPT"
            "ts", "tsx" -> "LANGUAGE_TYPESCRIPT"
            "html" -> "LANGUAGE_HTML"
            "css" -> "LANGUAGE_CSS"
            "json" -> "LANGUAGE_JSON"
            "md" -> "LANGUAGE_MARKDOWN"
            "sh" -> "LANGUAGE_SHELL"
            else -> "LANGUAGE_UNSPECIFIED"
        }
    }

    private fun assetExists(path: String): Boolean {
        return try {
            context.assets.open(path).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun guessMimeType(path: String): String {
        return when (path.substringAfterLast(".").lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun serveAsset(output: OutputStream, assetPath: String, mimeType: String) {
        try {
            val stream = context.assets.open(assetPath)
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mimeType\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray(StandardCharsets.UTF_8))
            val buf = ByteArray(16384)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
            }
            output.flush()
        } catch (e: Exception) {
            sendResponse(output, 500, "Internal Error", "text/plain", "Failed to load asset".toByteArray())
        }
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        cors: Boolean = false,
        isGrpc: Boolean = false
    ) {
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            if (isGrpc) {
                append("grpc-status: 0\r\n")
                append("grpc-message: OK\r\n")
            }
            if (cors) {
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, POST, OPTIONS, PUT, DELETE\r\n")
                append("Access-Control-Allow-Headers: Content-Type, x-codeium-csrf-token, Authorization, connect-protocol-version, connect-timeout-ms, x-grpc-web, x-user-agent\r\n")
                append("Access-Control-Expose-Headers: Content-Type, grpc-status, grpc-message, grpc-status-details-bin\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        if (body.isNotEmpty()) {
            output.write(body)
        }
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return if (sb.isEmpty() && c == -1) null else sb.toString()
    }
}
