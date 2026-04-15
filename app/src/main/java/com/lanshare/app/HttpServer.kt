package com.lanshare.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HttpServer(
    private val storage: StorageAccess,
    private val passwordManager: PasswordManager,
    private val port: Int
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        running.set(true)
        serverSocket = ServerSocket(port)
        acceptJob = scope.launch {
            while (running.get()) {
                val socket = try {
                    serverSocket?.accept()
                } catch (_: Throwable) {
                    null
                }
                if (socket != null) {
                    launch { handleClient(socket) }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        acceptJob?.cancel()
        scope.cancel()
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()
            val req = readRequest(input) ?: return
            try {
                route(req, output)
            } catch (_: Throwable) {
                writeText(output, 500, "Internal Server Error", "text/plain; charset=utf-8", "Internal Server Error", req.method == "HEAD")
            }
        }
    }

    private fun route(req: HttpRequest, out: OutputStream) {
        val method = req.method.uppercase(Locale.getDefault())
        val path = req.path
        if ((method == "POST") && path.startsWith("/upload")) {
            handleUpload(req, out)
            return
        }
        if ((method == "POST") && path.startsWith("/paste")) {
            handlePaste(req, out)
            return
        }
        if ((method == "POST") && path.startsWith("/zip")) {
            handleZip(req, out)
            return
        }
        if (method == "GET" || method == "HEAD") {
            handleGetOrHead(req, out)
            return
        }
        writeText(out, 405, "Method Not Allowed", "text/plain; charset=utf-8", "Method Not Allowed", method == "HEAD")
    }

    private fun handleGetOrHead(req: HttpRequest, out: OutputStream) {
        val decodedPath = decodeRequestPath(req.path)
        val relative = decodedPath.trim('/').trim()
        if (relative == ".passwords.json" || relative.startsWith(".passwords.json/")) {
            writeText(out, 403, "Forbidden", "text/plain; charset=utf-8", "Forbidden", req.method == "HEAD")
            return
        }

        if (storage.isDirectory(relative)) {
            val files = storage.listEntries(relative)
            val protected = kotlinx.coroutines.runBlocking { passwordManager.protectedFiles() }
            val html = HtmlRenderer.renderPage(files, relative, protected)
            val body = html.toByteArray(Charsets.UTF_8)
            val headers = linkedMapOf(
                "Content-Type" to "text/html; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "X-Swap-Snapshot" to snapshotOf(files),
                "Connection" to "close"
            )
            writeStatusLine(out, 200, "OK", headers)
            if (req.method != "HEAD") out.write(body)
            return
        }

        if (!storage.existsFile(relative)) {
            writeText(out, 404, "Not Found", "text/plain; charset=utf-8", "Not Found", req.method == "HEAD")
            return
        }

        val isProtected = kotlinx.coroutines.runBlocking { passwordManager.isProtected(relative) }
        if (isProtected) {
            val pw = req.query["pw"]
            val ok = kotlinx.coroutines.runBlocking { passwordManager.verify(relative, pw) }
            if (!ok) {
                writeText(out, 401, "Unauthorized", "text/plain; charset=utf-8", "Unauthorized", req.method == "HEAD")
                return
            }
        }

        val fileName = relative.substringAfterLast('/')
        val mime = FileUtils.guessMimeType(fileName)
        val size = storage.fileSize(relative)
        val headers = linkedMapOf(
            "Content-Type" to mime,
            "Content-Disposition" to FileUtils.contentDisposition(fileName),
            "Content-Length" to size.toString(),
            "Connection" to "close"
        )
        writeStatusLine(out, 200, "OK", headers)
        if (req.method != "HEAD") {
            storage.openInput(relative)?.use { it.copyTo(out) }
        }
    }

    private fun handleUpload(req: HttpRequest, out: OutputStream) {
        val targetPath = decodeRequestPath(req.path.removePrefix("/upload")).trim('/')
        if (!storage.isDirectory(targetPath)) {
            writeJson(out, 404, JSONObject().put("error", "target not found"))
            return
        }
        val ct = req.headers["content-type"] ?: ""
        val boundary = extractBoundary(ct)
        if (boundary.isNullOrBlank()) {
            writeJson(out, 400, JSONObject().put("error", "missing boundary"))
            return
        }
        val saved = parseMultipart(req.body, boundary).count { part ->
            val name = part.filename ?: return@count false
            storage.saveFile(targetPath, name, part.content) != null
        }
        writeJson(out, 200, JSONObject().put("saved", saved))
    }

    private fun handlePaste(req: HttpRequest, out: OutputStream) {
        val targetPath = decodeRequestPath(req.path.removePrefix("/paste")).trim('/')
        if (!storage.isDirectory(targetPath)) {
            writeJson(out, 404, JSONObject().put("error", "target not found"))
            return
        }
        val obj = try {
            JSONObject(req.body.toString(Charsets.UTF_8))
        } catch (_: Throwable) {
            writeJson(out, 400, JSONObject().put("error", "bad json"))
            return
        }
        val filenameRaw = obj.optString("filename", "note.txt")
        val text = obj.optString("text", "")
        val password = obj.optString("password", "")
        val filename = if (filenameRaw.endsWith(".txt")) filenameRaw else "$filenameRaw.txt"
        val saved = storage.saveText(targetPath, filename, text)
        if (saved == null) {
            writeJson(out, 500, JSONObject().put("error", "save failed"))
            return
        }
        val rel = if (targetPath.isBlank()) saved else "$targetPath/$saved"
        kotlinx.coroutines.runBlocking {
            if (password.isNotEmpty()) passwordManager.setPassword(rel, password)
            else passwordManager.setPassword(rel, "")
        }
        writeJson(out, 200, JSONObject().put("saved", saved))
    }

    private fun handleZip(req: HttpRequest, out: OutputStream) {
        val targetPath = decodeRequestPath(req.path.removePrefix("/zip")).trim('/')
        if (!storage.isDirectory(targetPath)) {
            writeText(out, 404, "Not Found", "text/plain; charset=utf-8", "Not Found", false)
            return
        }

        val body = req.body.toString(Charsets.UTF_8)
        val names = parseFormFields(body, "files")
        val protected = kotlinx.coroutines.runBlocking { passwordManager.protectedFiles() }
        val sources = storage.collectZipSources(targetPath, names) { rel -> rel in protected || rel == ".passwords.json" }
        if (sources.isEmpty()) {
            writeText(out, 404, "Not Found", "text/plain; charset=utf-8", "Nothing to zip", false)
            return
        }

        val zipName = if (targetPath.isBlank()) "LanShare.zip" else "${targetPath.substringAfterLast('/')}.zip"
        val headers = linkedMapOf(
            "Content-Type" to "application/zip",
            "Content-Disposition" to FileUtils.contentDisposition(zipName),
            "Connection" to "close"
        )
        writeStatusLine(out, 200, "OK", headers)
        ZipOutputStream(out).use { zos ->
            sources.forEach { src ->
                zos.putNextEntry(ZipEntry(src.entryName))
                src.inputOpener().use { input -> input.copyTo(zos) }
                zos.closeEntry()
            }
            zos.flush()
        }
    }

    private fun decodeRequestPath(path: String): String {
        val clean = path.substringBefore('?')
        val segments = clean.trim('/').split('/').filter { it.isNotBlank() }
        return segments.joinToString("/") { URLDecoder.decode(it, "UTF-8") }
    }

    private fun parseFormFields(raw: String, key: String): List<String> {
        val out = mutableListOf<String>()
        raw.split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@forEach
            val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            if (k != key) return@forEach
            val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            out += v
        }
        return out
    }

    private fun extractBoundary(contentType: String): String? {
        val parts = contentType.split(';').map { it.trim() }
        val marker = parts.firstOrNull { it.startsWith("boundary=") } ?: return null
        return marker.removePrefix("boundary=").trim('"')
    }

    private fun parseMultipart(body: ByteArray, boundary: String): List<MultipartPart> {
        val charset = Charset.forName("ISO-8859-1")
        val text = body.toString(charset)
        val delimiter = "--$boundary"
        val chunks = text.split(delimiter)
        val parts = mutableListOf<MultipartPart>()
        chunks.forEach { chunk ->
            if (chunk.isBlank()) return@forEach
            if (chunk.trim() == "--") return@forEach
            val clean = chunk.trimStart('\r', '\n').trimEnd('\r', '\n')
            if (clean == "--") return@forEach
            val hEnd = clean.indexOf("\r\n\r\n")
            if (hEnd < 0) return@forEach
            val headers = clean.substring(0, hEnd)
            val dataText = clean.substring(hEnd + 4)
            val filename = Regex("filename=\"([^\"]*)\"").find(headers)?.groupValues?.getOrNull(1)
            if (!filename.isNullOrBlank()) {
                val bytes = dataText.toByteArray(charset)
                parts += MultipartPart(filename = filename, content = bytes)
            }
        }
        return parts
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        val marker = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var match = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            headerBytes.write(b)
            if (b.toByte() == marker[match]) {
                match++
                if (match == marker.size) break
            } else {
                match = if (b.toByte() == marker[0]) 1 else 0
            }
            if (headerBytes.size() > 1024 * 1024) return null
        }

        val headerText = headerBytes.toByteArray().toString(Charsets.UTF_8)
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val reqLine = lines.first().split(' ')
        if (reqLine.size < 2) return null
        val method = reqLine[0].uppercase(Locale.getDefault())
        val target = reqLine[1]
        val path = target.substringBefore('?')
        val queryRaw = target.substringAfter('?', "")
        val headers = mutableMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase(Locale.getDefault())
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val b = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val r = input.read(b, read, contentLength - read)
                if (r == -1) break
                read += r
            }
            if (read < contentLength) b.copyOf(read) else b
        } else ByteArray(0)
        val query = mutableMapOf<String, String>()
        if (queryRaw.isNotBlank()) {
            queryRaw.split('&').forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                    val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    query[k] = v
                } else if (pair.isNotBlank()) {
                    query[URLDecoder.decode(pair, "UTF-8")] = ""
                }
            }
        }
        return HttpRequest(method, path, query, headers, body)
    }

    private fun writeJson(out: OutputStream, code: Int, obj: JSONObject) {
        writeText(out, code, reason(code), "application/json; charset=utf-8", obj.toString(), false)
    }

    private fun writeText(out: OutputStream, code: Int, reason: String, type: String, content: String, headOnly: Boolean) {
        val body = content.toByteArray(Charsets.UTF_8)
        val headers = linkedMapOf(
            "Content-Type" to type,
            "Content-Length" to body.size.toString(),
            "Connection" to "close"
        )
        writeStatusLine(out, code, reason, headers)
        if (!headOnly) out.write(body)
    }

    private fun writeStatusLine(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun reason(code: Int): String {
        return when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "OK"
        }
    }

    private fun snapshotOf(files: List<FileEntry>): String {
        return files
            .joinToString("|") { "${it.name}:${it.size}:${it.isDirectory}:${it.relativePath}" }
            .hashCode()
            .toString()
    }

    private data class MultipartPart(val filename: String?, val content: ByteArray)
    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray
    )
}
