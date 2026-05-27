package com.lanshare.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.DecimalFormat
import java.util.Locale

interface StorageBackend {
fun ensureRootExists(): Boolean
fun displayPath(): String
fun effectiveLocalPathOrNull(): String?
fun listEntries(relativePath: String): List<FileEntry>
fun isDirectory(relativePath: String): Boolean
fun existsFile(relativePath: String): Boolean
fun openInput(relativePath: String): InputStream?
fun fileSize(relativePath: String): Long
fun saveFile(relativeDir: String, originalName: String, bytes: ByteArray): String?
fun saveText(relativeDir: String, originalName: String, text: String): String?
fun readPasswordsJson(): String
fun writePasswordsJson(content: String): Boolean
fun collectZipSources(relativeDir: String, selectedNames: List<String>, skipRelative: (String) -> Boolean): List<ZipSource>
}

data class FileEntry(
val name: String,
val isDirectory: Boolean,
val size: Long,
val relativePath: String,
val modifiedAt: Long
)

data class ZipSource(
val entryName: String,
val inputOpener: () -> InputStream
)

class StorageAccess(private val context: Context, private val treeUri: String?) : StorageBackend {
override fun ensureRootExists(): Boolean {
val root = rootDocUri() ?: return false
return isDocumentTreeDir(root)
}

override fun displayPath(): String = treeUri ?: "Pick a folder"

override fun effectiveLocalPathOrNull(): String? = null

override fun listEntries(relativePath: String): List<FileEntry> {
val clean = normalizeRelativePath(relativePath) ?: return emptyList()
return listEntriesSaf(clean)
}

override fun isDirectory(relativePath: String): Boolean {
val clean = normalizeRelativePath(relativePath) ?: return false
if (clean.isBlank()) return true
val uri = resolveSafUri(clean) ?: return false
return isDocumentTreeDir(uri)
}

override fun existsFile(relativePath: String): Boolean {
val clean = normalizeRelativePath(relativePath) ?: return false
val uri = resolveSafUri(clean) ?: return false
return !isDocumentTreeDir(uri)
}

override fun openInput(relativePath: String): InputStream? {
val clean = normalizeRelativePath(relativePath) ?: return null
val uri = resolveSafUri(clean) ?: return null
if (isDocumentTreeDir(uri)) return null
return context.contentResolver.openInputStream(uri)
}

override fun fileSize(relativePath: String): Long {
val clean = normalizeRelativePath(relativePath) ?: return 0L
val uri = resolveSafUri(clean) ?: return 0L
val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return 0L
return pfd.use { if (it.statSize > 0) it.statSize else 0L }
}

override fun saveFile(relativeDir: String, originalName: String, bytes: ByteArray): String? {
val cleanDir = normalizeRelativePath(relativeDir) ?: return null
val safeName = sanitizeName(originalName)
if (safeName.isBlank()) return null
return saveFileSaf(cleanDir, safeName, bytes)
}

override fun saveText(relativeDir: String, originalName: String, text: String): String? {
return saveFile(relativeDir, originalName, text.toByteArray(Charsets.UTF_8))
}

override fun readPasswordsJson(): String {
val stream = openInput(".passwords.json") ?: return "{}"
return stream.use { it.readBytes().toString(Charsets.UTF_8) }
}

override fun writePasswordsJson(content: String): Boolean {
val root = rootDocUri() ?: return false
val existing = findChild(root, ".passwords.json")
val uri = existing ?: createFile(root, ".passwords.json", "application/json") ?: return false
return context.contentResolver.openOutputStream(uri, "wt")?.use {
it.write(content.toByteArray(Charsets.UTF_8))
} != null
}

override fun collectZipSources(relativeDir: String, selectedNames: List<String>, skipRelative: (String) -> Boolean): List<ZipSource> {
val cleanDir = normalizeRelativePath(relativeDir) ?: return emptyList()
val out = mutableListOf<ZipSource>()
for (name in selectedNames) {
val safe = sanitizeName(name)
if (safe.isBlank()) continue
val rel = joinPath(cleanDir, safe)
if (safe == ".passwords.json" || skipRelative(rel)) continue
val uri = resolveSafUri(rel) ?: continue
if (isDocumentTreeDir(uri)) {
collectDirSaf(uri, safe, cleanDir, out, skipRelative)
} else {
out.add(ZipSource(safe) { context.contentResolver.openInputStream(uri) ?: ByteArrayInputStream(ByteArray(0)) })
}
}
return out
}

private fun listEntriesSaf(clean: String): List<FileEntry> {
val dirUri = if (clean.isBlank()) rootDocUri() else resolveSafUri(clean)
if (dirUri == null || !isDocumentTreeDir(dirUri)) return emptyList()
val children = listChildren(dirUri)
return children
.filter { nameOf(it) != ".passwords.json" }
.sortedWith(compareBy<Uri> { !isDocumentTreeDir(it) }.thenBy { (nameOf(it) ?: "").lowercase(Locale.getDefault()) })
.mapNotNull { uri ->
val name = nameOf(uri) ?: return@mapNotNull null
FileEntry(
name = name,
isDirectory = isDocumentTreeDir(uri),
size = if (isDocumentTreeDir(uri)) 0L else fileSize(joinPath(clean, name)),
relativePath = joinPath(clean, name),
modifiedAt = lastModifiedOf(uri)
)
}
}

private fun saveFileSaf(relativeDir: String, desiredName: String, bytes: ByteArray): String? {
val dirUri = ensureSafDirectory(relativeDir) ?: return null
val finalName = uniqueNameSaf(dirUri, desiredName)
val uri = createFile(dirUri, finalName, FileUtils.guessMimeType(finalName)) ?: return null
context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: return null
return finalName
}

private fun uniqueNameSaf(dirUri: Uri, desired: String): String {
if (findChild(dirUri, desired) == null) return desired
val dot = desired.lastIndexOf('.')
val base = if (dot > 0) desired.substring(0, dot) else desired
val ext = if (dot > 0) desired.substring(dot) else ""
var i = 1
while (true) {
val candidate = "${base}_$i$ext"
if (findChild(dirUri, candidate) == null) return candidate
i++
}
}

private fun collectDirSaf(dirUri: Uri, prefix: String, baseDir: String, out: MutableList<ZipSource>, skip: (String) -> Boolean) {
listChildren(dirUri).sortedBy { nameOf(it)?.lowercase(Locale.getDefault()) ?: "" }.forEach { childUri ->
val childName = nameOf(childUri) ?: return@forEach
val nextPrefix = "$prefix/$childName"
val rel = joinPath(baseDir, nextPrefix)
if (childName == ".passwords.json" || skip(rel)) return@forEach
if (isDocumentTreeDir(childUri)) {
collectDirSaf(childUri, nextPrefix, baseDir, out, skip)
} else {
out.add(ZipSource(nextPrefix) {
context.contentResolver.openInputStream(childUri) ?: ByteArrayInputStream(ByteArray(0))
})
}
}
}

private fun rootDocUri(): Uri? {
val t = treeUri ?: return null
return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(t), DocumentsContract.getTreeDocumentId(Uri.parse(t)))
}

private fun ensureSafDirectory(relativeDir: String): Uri? {
var current = rootDocUri() ?: return null
if (relativeDir.isBlank()) return current
val segments = relativeDir.split('/').filter { it.isNotBlank() }
for (seg in segments) {
val existing = findChild(current, seg)
current = existing ?: createDirectory(current, seg) ?: return null
if (!isDocumentTreeDir(current)) return null
}
return current
}

private fun resolveSafUri(relativePath: String): Uri? {
var current = rootDocUri() ?: return null
if (relativePath.isBlank()) return current
val segments = relativePath.split('/').filter { it.isNotBlank() }
for (seg in segments) {
val next = findChild(current, seg) ?: return null
current = next
}
return current
}

private fun listChildren(dirUri: Uri): List<Uri> {
val childDocs = mutableListOf<Uri>()
val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, DocumentsContract.getDocumentId(dirUri))
val cursor = context.contentResolver.query(
childrenUri,
arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
null,
null,
null
) ?: return emptyList()
cursor.use {
while (it.moveToNext()) {
val docId = it.getString(0)
childDocs.add(DocumentsContract.buildDocumentUriUsingTree(dirUri, docId))
}
}
return childDocs
}

private fun findChild(parentUri: Uri, name: String): Uri? {
val children = listChildren(parentUri)
return children.firstOrNull { nameOf(it) == name }
}

private fun nameOf(uri: Uri): String? {
val cursor = context.contentResolver.query(
uri,
arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
null,
null,
null
) ?: return null
cursor.use {
if (it.moveToFirst()) return it.getString(0)
}
return null
}

private fun mimeOf(uri: Uri): String? {
val cursor = context.contentResolver.query(
uri,
arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
null,
null,
null
) ?: return null
cursor.use {
if (it.moveToFirst()) return it.getString(0)
}
return null
}

private fun lastModifiedOf(uri: Uri): Long {
val cursor = context.contentResolver.query(
uri,
arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
null,
null,
null
) ?: return 0L
cursor.use {
if (it.moveToFirst()) return it.getLong(0)
}
return 0L
}

private fun isDocumentTreeDir(uri: Uri): Boolean {
val mime = mimeOf(uri) ?: return false
return mime == DocumentsContract.Document.MIME_TYPE_DIR
}

private fun createDirectory(parent: Uri, name: String): Uri? {
return DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
}

private fun createFile(parent: Uri, name: String, mime: String): Uri? {
return DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
}

private fun normalizeRelativePath(path: String): String? {
val raw = path.trim().trim('/')
if (raw.isBlank()) return ""
val segments = raw.split('/').filter { it.isNotBlank() }
if (segments.any { it == "." || it == ".." }) return null
return segments.joinToString("/") { sanitizeName(it) }
}

private fun sanitizeName(name: String): String {
return name.replace("\\", "").replace("/", "").trim()
}

companion object {
fun joinPath(base: String, child: String): String {
if (base.isBlank()) return child.trim('/')
if (child.isBlank()) return base.trim('/')
return "${base.trim('/')}/${child.trim('/')}"
}
}
}

object FileUtils {
private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "heic")
private val videoExt = setOf("mp4", "mov", "avi", "mkv")
private val audioExt = setOf("mp3", "wav", "flac", "m4a")
private val archiveExt = setOf("zip", "tar", "gz", "rar")

fun iconFor(name: String): String {
val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
return when {
ext in imageExt -> "🖼️"
ext in videoExt -> "🎬"
ext in audioExt -> "🎵"
ext == "pdf" -> "📄"
ext == "doc" || ext == "docx" -> "📝"
ext == "txt" -> "📃"
ext in archiveExt -> "🗜️"
ext == "py" -> "🐍"
ext == "js" -> "📜"
ext == "html" -> "🌐"
ext == "css" -> "🎨"
else -> "📄"
}
}

fun formatSize(size: Long): String {
if (size <= 0) return "0 B"
val units = arrayOf("B", "KB", "MB", "GB", "TB")
var value = size.toDouble()
var idx = 0
while (value >= 1024 && idx < units.lastIndex) {
value /= 1024
idx++
}
return "${DecimalFormat("0.#").format(value)} ${units[idx]}"
}

fun encodePathSegments(path: String): String {
val clean = path.trim().trim('/')
if (clean.isBlank()) return ""
return clean.split('/').filter { it.isNotBlank() }.joinToString("/") {
URLEncoder.encode(it, "UTF-8").replace("+", "%20")
}
}

fun decodePathSegments(path: String): String {
val clean = path.trim().trim('/')
if (clean.isBlank()) return ""
return clean.split('/').filter { it.isNotBlank() }.joinToString("/") {
URLDecoder.decode(it, "UTF-8")
}
}

fun guessMimeType(name: String): String {
val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

fun contentDisposition(name: String): String {
val asciiFallback = name.map { if (it.code in 32..126) it else '_' }.joinToString("")
val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
return "attachment; filename=\"$asciiFallback\"; filename*=UTF-8''$encoded"
}

fun randomHex16(): String {
val bytes = ByteArray(16)
SecureRandom().nextBytes(bytes)
return bytes.joinToString("") { "%02x".format(it) }
}
}
