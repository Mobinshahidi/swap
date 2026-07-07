package com.lanshare.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HttpServer(
private val storage: StorageAccess,
private val passwordManager: PasswordManager,
private val port: Int,
private val basicAuthUser: String = "",
private val basicAuthPassword: String = ""
) {
private companion object {
// Close a kept-alive connection that has been idle this long, and use the
// same window as the advertised Keep-Alive timeout.
const val IDLE_TIMEOUT_MS = 20_000
const val KEEP_ALIVE_HEADER = "timeout=20"
}

private val basicAuthEnabled = basicAuthUser.isNotEmpty() || basicAuthPassword.isNotEmpty()
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
// Disable Nagle so the status line, headers, and body are not held back on
// delayed ACKs (those stalls compound to seconds over a power-saving link),
// and reap idle kept-alive sockets after IDLE_TIMEOUT_MS.
runCatching {
s.tcpNoDelay = true
s.soTimeout = IDLE_TIMEOUT_MS
}
val input = BufferedInputStream(s.getInputStream())
val output = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
// HTTP/1.1 keep-alive: serve requests on this one socket until the client
// asks to close, a response cannot be framed for reuse, or the socket goes
// idle. Reusing the connection avoids a fresh (multi-second) TCP handshake
// per request.
while (running.get()) {
val req = try {
readRequest(input)
} catch (_: SocketTimeoutException) {
null
} catch (_: Throwable) {
null
} ?: break
val keepAlive = wantsKeepAlive(req)
val shouldClose = try {
route(req, output, keepAlive)
} catch (_: Throwable) {
runCatching {
writeText(output, 500, "Internal Server Error", "text/plain; charset=utf-8", "Internal Server Error", req.method == "HEAD", false)
}
true
}
runCatching { output.flush() }
if (shouldClose || !keepAlive) break
}
}
}

private fun wantsKeepAlive(req: HttpRequest): Boolean {
val connection = req.headers["connection"]?.lowercase(Locale.getDefault()) ?: ""
if (connection.contains("close")) return false
if (connection.contains("keep-alive")) return true
// Default: HTTP/1.1 keeps the connection alive, HTTP/1.0 closes it.
return !req.version.endsWith("1.0")
}

// Handles one request. Returns true when the connection must be closed
// afterwards (unframed/streamed response), false when it may be kept alive.
private fun route(req: HttpRequest, out: OutputStream, keepAlive: Boolean): Boolean {
val method = req.method.uppercase(Locale.getDefault())
val path = req.path
// Optional server-wide HTTP Basic Auth. This is a cheap header check that
// gates every request; per-file passwords are handled separately and stay
// untouched.
if (basicAuthEnabled && !basicAuthOk(req)) {
writeUnauthorized(out, method == "HEAD", keepAlive)
return !keepAlive
}
if ((method == "POST") && path.startsWith("/upload")) {
handleUpload(req, out, keepAlive)
return !keepAlive
}
if ((method == "POST") && path.startsWith("/paste")) {
handlePaste(req, out, keepAlive)
return !keepAlive
}
if ((method == "POST") && path.startsWith("/zip")) {
handleZip(req, out)
// Zip is streamed without a Content-Length, so the socket must close to
// mark the end of the body.
return true
}
if (method == "GET" && path.startsWith("/__snapshot")) {
val rel = decodeRequestPath(path.removePrefix("/__snapshot")).trim('/')
writeText(out, 200, "OK", "text/plain; charset=utf-8", currentSnapshot(rel), false, keepAlive)
return !keepAlive
}
if (method == "GET" || method == "HEAD") {
handleGetOrHead(req, out, keepAlive)
return !keepAlive
}
writeText(out, 405, "Method Not Allowed", "text/plain; charset=utf-8", "Method Not Allowed", method == "HEAD", keepAlive)
return !keepAlive
}

private fun handleGetOrHead(req: HttpRequest, out: OutputStream, keepAlive: Boolean) {
val decodedPath = decodeRequestPath(req.path)
val relative = decodedPath.trim('/').trim()
if (relative == ".passwords.json" || relative.startsWith(".passwords.json/")) {
writeText(out, 403, "Forbidden", "text/plain; charset=utf-8", "Forbidden", req.method == "HEAD", keepAlive)
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
"X-Swap-Snapshot" to snapshotOf(files)
)
applyConnectionHeaders(headers, keepAlive)
writeStatusLine(out, 200, "OK", headers)
if (req.method != "HEAD") out.write(body)
return
}

if (!storage.existsFile(relative)) {
writeText(out, 404, "Not Found", "text/plain; charset=utf-8", "Not Found", req.method == "HEAD", keepAlive)
return
}

val isProtected = kotlinx.coroutines.runBlocking { passwordManager.isProtected(relative) }
if (isProtected) {
val pw = req.query["pw"]
val ok = kotlinx.coroutines.runBlocking { passwordManager.verify(relative, pw) }
if (!ok) {
writeText(out, 401, "Unauthorized", "text/plain; charset=utf-8", "Unauthorized", req.method == "HEAD", keepAlive)
return
}
}

val fileName = relative.substringAfterLast('/')
val mime = FileUtils.guessMimeType(fileName)
val size = storage.fileSize(relative)
val headers = linkedMapOf(
"Content-Type" to mime,
"Content-Disposition" to FileUtils.contentDisposition(fileName),
"Content-Length" to size.toString()
)
applyConnectionHeaders(headers, keepAlive)
writeStatusLine(out, 200, "OK", headers)
if (req.method != "HEAD") {
storage.openInput(relative)?.use { it.copyTo(out, 64 * 1024) }
}
}

private fun handleUpload(req: HttpRequest, out: OutputStream, keepAlive: Boolean) {
val targetPath = decodeRequestPath(req.path.removePrefix("/upload")).trim('/')
if (!storage.isDirectory(targetPath)) {
writeJson(out, 404, JSONObject().put("error", "target not found"), keepAlive)
return
}
val ct = req.headers["content-type"] ?: ""
val boundary = extractBoundary(ct)
if (boundary.isNullOrBlank()) {
writeJson(out, 400, JSONObject().put("error", "missing boundary"), keepAlive)
return
}
val saved = parseMultipart(req.body, boundary).count { part ->
val name = part.filename ?: return@count false
storage.saveFile(targetPath, name, part.content) != null
}
writeJson(out, 200, JSONObject().put("saved", saved), keepAlive)
}

private fun handlePaste(req: HttpRequest, out: OutputStream, keepAlive: Boolean) {
val targetPath = decodeRequestPath(req.path.removePrefix("/paste")).trim('/')
if (!storage.isDirectory(targetPath)) {
writeJson(out, 404, JSONObject().put("error", "target not found"), keepAlive)
return
}
val obj = try {
JSONObject(req.body.toString(Charsets.UTF_8))
} catch (_: Throwable) {
writeJson(out, 400, JSONObject().put("error", "bad json"), keepAlive)
return
}
val filenameRaw = obj.optString("filename", "note.txt")
val text = obj.optString("text", "")
val password = obj.optString("password", "")
val filename = if (filenameRaw.endsWith(".txt")) filenameRaw else "$filenameRaw.txt"
val saved = storage.saveText(targetPath, filename, text)
if (saved == null) {
writeJson(out, 500, JSONObject().put("error", "save failed"), keepAlive)
return
}
val rel = if (targetPath.isBlank()) saved else "$targetPath/$saved"
kotlinx.coroutines.runBlocking {
if (password.isNotEmpty()) passwordManager.setPassword(rel, password)
else passwordManager.setPassword(rel, "")
}
writeJson(out, 200, JSONObject().put("saved", saved), keepAlive)
}

// Cheap change-detection token for the folder-change poller. Returns the same
// snapshot string embedded in the page, so the client can detect changes with a
// tiny GET instead of a held-open connection.
private fun currentSnapshot(relative: String): String {
return if (storage.isDirectory(relative)) snapshotOf(storage.listEntries(relative)) else ""
}

private fun handleZip(req: HttpRequest, out: OutputStream) {
val targetPath = decodeRequestPath(req.path.removePrefix("/zip")).trim('/')
if (!storage.isDirectory(targetPath)) {
writeText(out, 404, "Not Found", "text/plain; charset=utf-8", "Not Found", false, false)
return
}

val body = req.body.toString(Charsets.UTF_8)
val names = parseFormFields(body, "files")
val protected = kotlinx.coroutines.runBlocking { passwordManager.protectedFiles() }
val sources = storage.collectZipSources(targetPath, names) { rel -> rel in protected || rel == ".passwords.json" }
if (sources.isEmpty()) {
writeText(out, 404, "Not Found", "text/plain; charset=utf-8", "Nothing to zip", false, false)
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
		val delimiter = "--$boundary".toByteArray(Charsets.UTF_8)
		val parts = mutableListOf<MultipartPart>()
		var pos = 0

		while (pos < body.size) {
			val boundaryPos = indexOfBytes(body, delimiter, pos)
			if (boundaryPos < 0) break
			pos = boundaryPos + delimiter.size

			if (pos + 1 < body.size && body[pos] == '\r'.code.toByte() && body[pos + 1] == '\n'.code.toByte()) {
				pos += 2
			} else if (pos < body.size && body[pos] == '-'.code.toByte()) {
				break
			} else {
				break
			}

			val headerEnd = indexOfBytes(body, "\r\n\r\n".toByteArray(), pos)
			if (headerEnd < 0) break

			val headerText = body.copyOfRange(pos, headerEnd).toString(Charsets.ISO_8859_1)
			pos = headerEnd + 4

			val nextBoundary = indexOfBytes(body, delimiter, pos)
			if (nextBoundary < 0) break
			val bodyEnd = nextBoundary - 2
			if (bodyEnd <= pos) {
				pos = nextBoundary
				continue
			}

			val filename = extractMultipartFilename(headerText)
			if (filename == null) {
				pos = nextBoundary
				continue
			}
			val content = body.copyOfRange(pos, bodyEnd)
			parts += MultipartPart(filename = filename, content = content)
			pos = nextBoundary
		}
		return parts
}

	private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, start: Int = 0): Int {
		if (needle.isEmpty() || haystack.isEmpty() || start >= haystack.size) return -1
		outer@ for (i in start..(haystack.size - needle.size)) {
			for (j in needle.indices) {
				if (haystack[i + j] != needle[j]) continue@outer
			}
			return i
		}
		return -1
	}

private fun extractMultipartFilename(headers: String): String? {
val cdLine = headers.lineSequence()
.firstOrNull { it.startsWith("Content-Disposition:", ignoreCase = true) }
?: return null
val cdValue = cdLine.substringAfter(':').trim()
val filenameStar = Regex("""filename\*=(?:UTF-8'')?([^;]+)""", RegexOption.IGNORE_CASE)
.find(cdValue)?.groupValues?.getOrNull(1)
if (!filenameStar.isNullOrBlank()) {
return runCatching { URLDecoder.decode(filenameStar.trim().trim('"'), "UTF-8") }.getOrNull()
}
val filename = Regex("filename=\"([^\"]*)\"").find(cdValue)?.groupValues?.getOrNull(1)
?: Regex("filename=([^;]+)").find(cdValue)?.groupValues?.getOrNull(1)
if (filename.isNullOrBlank()) return null
val raw = filename.trim().trim('"')
return runCatching {
val bytes = raw.toByteArray(Charset.forName("ISO-8859-1"))
String(bytes, Charsets.UTF_8)
}.getOrDefault(raw)
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
val version = reqLine.getOrNull(2)?.uppercase(Locale.getDefault()) ?: "HTTP/1.1"
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
return HttpRequest(method, path, query, headers, body, version)
}

private fun basicAuthOk(req: HttpRequest): Boolean {
val header = req.headers["authorization"] ?: return false
if (!header.regionMatches(0, "Basic ", 0, 6, ignoreCase = true)) return false
val decoded = try {
String(Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8)
} catch (_: Throwable) {
return false
}
val idx = decoded.indexOf(':')
if (idx < 0) return false
val user = decoded.substring(0, idx)
val pass = decoded.substring(idx + 1)
return constantTimeEquals(user, basicAuthUser) and constantTimeEquals(pass, basicAuthPassword)
}

private fun constantTimeEquals(a: String, b: String): Boolean {
val ab = a.toByteArray(Charsets.UTF_8)
val bb = b.toByteArray(Charsets.UTF_8)
var diff = ab.size xor bb.size
for (i in ab.indices) {
diff = diff or (ab[i].toInt() xor bb.getOrElse(i) { 0 }.toInt())
}
return diff == 0
}

private fun writeUnauthorized(out: OutputStream, headOnly: Boolean, keepAlive: Boolean) {
val body = "Unauthorized".toByteArray(Charsets.UTF_8)
val headers = linkedMapOf(
"Content-Type" to "text/plain; charset=utf-8",
"Content-Length" to body.size.toString(),
"WWW-Authenticate" to "Basic realm=\"Swap\", charset=\"UTF-8\""
)
applyConnectionHeaders(headers, keepAlive)
writeStatusLine(out, 401, "Unauthorized", headers)
if (!headOnly) out.write(body)
}

private fun writeJson(out: OutputStream, code: Int, obj: JSONObject, keepAlive: Boolean) {
writeText(out, code, reason(code), "application/json; charset=utf-8", obj.toString(), false, keepAlive)
}

private fun writeText(out: OutputStream, code: Int, reason: String, type: String, content: String, headOnly: Boolean, keepAlive: Boolean) {
val body = content.toByteArray(Charsets.UTF_8)
val headers = linkedMapOf(
"Content-Type" to type,
"Content-Length" to body.size.toString()
)
applyConnectionHeaders(headers, keepAlive)
writeStatusLine(out, code, reason, headers)
if (!headOnly) out.write(body)
}

private fun applyConnectionHeaders(headers: MutableMap<String, String>, keepAlive: Boolean) {
if (keepAlive) {
headers["Connection"] = "keep-alive"
headers["Keep-Alive"] = KEEP_ALIVE_HEADER
} else {
headers["Connection"] = "close"
}
}

private fun writeStatusLine(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
val sb = StringBuilder()
sb.append("HTTP/1.1 $code $reason\r\n")
headers.forEach { (k, v) ->
val value = if (k.equals("Content-Type", ignoreCase = true)) normalizeContentType(v) else v
sb.append(k).append(": ").append(value).append("\r\n")
}
sb.append("\r\n")
out.write(sb.toString().toByteArray(Charsets.UTF_8))
}

private fun normalizeContentType(value: String): String {
return if (value.contains("charset=", ignoreCase = true)) value else "$value; charset=utf-8"
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
val body: ByteArray,
val version: String
)
}
