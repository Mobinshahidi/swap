package com.lanshare.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
fun openInputAt(relativePath: String, offset: Long): InputStream?
fun fileSize(relativePath: String): Long
fun saveFile(relativeDir: String, originalName: String, bytes: ByteArray): String?
fun saveText(relativeDir: String, originalName: String, text: String): String?
fun readPasswordsJson(): String
fun writePasswordsJson(content: String): Boolean
fun collectZipSources(relativeDir: String, selectedNames: List<String>, skipRelative: (String) -> Boolean): List<ZipSource>
fun delete(relativePath: String): Boolean
fun rename(relativePath: String, newName: String): String?
fun createFolder(relativeDir: String, name: String): String?
fun move(relativePath: String, targetDir: String): Boolean
fun searchTree(query: String, limit: Int): List<FileEntry>
fun thumbnailJpeg(relativePath: String, size: Int): ByteArray?
fun moveToTrash(relativePath: String): Boolean
fun restoreFromTrash(trashName: String): Boolean
fun emptyTrash(): List<String>
fun trashEntries(): List<TrashEntry>
}

data class TrashEntry(
val name: String,
val isDirectory: Boolean,
val size: Long,
val original: String
)

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

// Opens a file positioned at `offset`, for serving HTTP Range requests (video
// seeking, resumable downloads). Uses a seekable file descriptor so we skip to
// the offset instead of reading and discarding the leading bytes.
override fun openInputAt(relativePath: String, offset: Long): InputStream? {
val clean = normalizeRelativePath(relativePath) ?: return null
val uri = resolveSafUri(clean) ?: return null
if (isDocumentTreeDir(uri)) return null
val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
if (offset > 0) {
val ok = runCatching { stream.channel.position(offset) }.isSuccess
if (!ok) {
runCatching { stream.close() }
return null
}
}
return stream
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

override fun delete(relativePath: String): Boolean {
val clean = normalizeRelativePath(relativePath) ?: return false
if (clean.isBlank() || clean == ".passwords.json") return false
val uri = resolveSafUri(clean) ?: return false
return runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
}

override fun rename(relativePath: String, newName: String): String? {
val clean = normalizeRelativePath(relativePath) ?: return null
if (clean.isBlank() || clean == ".passwords.json") return null
val safe = sanitizeName(newName)
if (safe.isBlank() || safe == ".passwords.json") return null
val uri = resolveSafUri(clean) ?: return null
val newUri = runCatching { DocumentsContract.renameDocument(context.contentResolver, uri, safe) }.getOrNull() ?: return null
// The provider may append a numeric suffix on collision, so read the name back.
return nameOfDoc(newUri) ?: safe
}

override fun createFolder(relativeDir: String, name: String): String? {
val cleanDir = normalizeRelativePath(relativeDir) ?: return null
val safe = sanitizeName(name)
if (safe.isBlank() || safe == ".passwords.json") return null
val parent = ensureSafDirectory(cleanDir) ?: return null
if (findChild(parent, safe) != null) return null
val created = createDirectory(parent, safe) ?: return null
return nameOfDoc(created) ?: safe
}

override fun move(relativePath: String, targetDir: String): Boolean {
val cleanSrc = normalizeRelativePath(relativePath) ?: return false
if (cleanSrc.isBlank() || cleanSrc == ".passwords.json") return false
val cleanDst = normalizeRelativePath(targetDir) ?: return false
val srcUri = resolveSafUri(cleanSrc) ?: return false
val srcParentRel = if (cleanSrc.contains('/')) cleanSrc.substringBeforeLast('/') else ""
val srcParentUri = if (srcParentRel.isBlank()) rootDocUri() else resolveSafUri(srcParentRel)
srcParentUri ?: return false
val dstUri = ensureSafDirectory(cleanDst) ?: return false
if (srcParentUri == dstUri) return true
return runCatching {
DocumentsContract.moveDocument(context.contentResolver, srcUri, srcParentUri, dstUri) != null
}.getOrDefault(false)
}

override fun searchTree(query: String, limit: Int): List<FileEntry> {
val q = query.trim().lowercase(Locale.getDefault())
if (q.isBlank()) return emptyList()
val root = rootDocUri() ?: return emptyList()
val out = mutableListOf<FileEntry>()
searchRec(root, "", q, out, limit)
return out
}

private fun searchRec(dirUri: Uri, relDir: String, q: String, out: MutableList<FileEntry>, limit: Int) {
if (out.size >= limit) return
for (child in listChildDocs(dirUri)) {
if (out.size >= limit) return
if (child.name == ".passwords.json" || child.name == ".trash") continue
val rel = if (relDir.isBlank()) child.name else "$relDir/${child.name}"
if (child.name.lowercase(Locale.getDefault()).contains(q)) {
out.add(FileEntry(child.name, child.isDirectory, if (child.isDirectory) 0L else child.size, rel, child.modified))
}
if (child.isDirectory) searchRec(child.uri, rel, q, out, limit)
}
}

override fun thumbnailJpeg(relativePath: String, size: Int): ByteArray? {
val clean = normalizeRelativePath(relativePath) ?: return null
val uri = resolveSafUri(clean) ?: return null
if (isDocumentTreeDir(uri)) return null
val bmp = runCatching {
DocumentsContract.getDocumentThumbnail(context.contentResolver, uri, Point(size, size), null)
}.getOrNull() ?: return null
val bos = ByteArrayOutputStream()
return runCatching {
bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
bos.toByteArray()
}.getOrNull()
}

// Soft-delete: move an item into the hidden .trash folder and remember where it
// came from, so it can be restored. Collisions in .trash are renamed uniquely.
override fun moveToTrash(relativePath: String): Boolean {
val clean = normalizeRelativePath(relativePath) ?: return false
if (clean.isBlank() || clean == ".passwords.json" || clean == ".trash" || clean.startsWith(".trash/")) return false
val name = clean.substringAfterLast('/')
val trashDir = ensureSafDirectory(".trash") ?: return false
var trashName = name
var sourceRel = clean
if (findChild(trashDir, name) != null) {
val unique = uniqueNameSaf(trashDir, name)
val renamed = rename(clean, unique) ?: return false
trashName = renamed
val parent = if (clean.contains('/')) clean.substringBeforeLast('/') else ""
sourceRel = if (parent.isBlank()) trashName else "$parent/$trashName"
}
if (!move(sourceRel, ".trash")) return false
val idx = readTrashIndex()
idx.put(trashName, clean)
writeTrashIndex(idx)
return true
}

override fun restoreFromTrash(trashName: String): Boolean {
val safe = sanitizeName(trashName)
if (safe.isBlank()) return false
val idx = readTrashIndex()
val original = idx.optString(safe, "")
if (original.isBlank()) return false
val origParent = if (original.contains('/')) original.substringBeforeLast('/') else ""
val origName = original.substringAfterLast('/')
if (!move(".trash/$safe", origParent)) return false
if (origName != safe) {
val movedRel = if (origParent.isBlank()) safe else "$origParent/$safe"
rename(movedRel, origName)
}
idx.remove(safe)
writeTrashIndex(idx)
return true
}

override fun emptyTrash(): List<String> {
val idx = readTrashIndex()
val originals = idx.keys().asSequence().map { idx.optString(it, "") }.filter { it.isNotBlank() }.toList()
val trashDir = resolveSafUri(".trash")
if (trashDir != null) {
for (child in listChildDocs(trashDir)) {
runCatching { DocumentsContract.deleteDocument(context.contentResolver, child.uri) }
}
}
writeTrashIndex(JSONObject())
return originals
}

override fun trashEntries(): List<TrashEntry> {
val trashDir = resolveSafUri(".trash") ?: return emptyList()
val idx = readTrashIndex()
return listChildDocs(trashDir)
.filter { it.name != ".index.json" }
.map { child ->
TrashEntry(
name = child.name,
isDirectory = child.isDirectory,
size = if (child.isDirectory) 0L else child.size,
original = idx.optString(child.name, child.name)
)
}
}

private fun readTrashIndex(): JSONObject {
val uri = resolveSafUri(".trash/.index.json") ?: return JSONObject()
return runCatching {
context.contentResolver.openInputStream(uri)?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } ?: JSONObject()
}.getOrDefault(JSONObject())
}

private fun writeTrashIndex(obj: JSONObject): Boolean {
val trashDir = ensureSafDirectory(".trash") ?: return false
val existing = findChild(trashDir, ".index.json")
val uri = existing ?: createFile(trashDir, ".index.json", "application/json") ?: return false
return context.contentResolver.openOutputStream(uri, "wt")?.use {
it.write(obj.toString().toByteArray(Charsets.UTF_8))
} != null
}

private fun nameOfDoc(uri: Uri): String? {
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

private fun listEntriesSaf(clean: String): List<FileEntry> {
val dirUri = if (clean.isBlank()) rootDocUri() else resolveSafUri(clean)
if (dirUri == null || !isDocumentTreeDir(dirUri)) return emptyList()
// A single SAF cursor returns name/type/size/mtime for every child at once.
// The previous code issued per-file metadata queries (and re-walked the tree
// for each file's size), which made large folders take many seconds.
return listChildDocs(dirUri)
.asSequence()
.filter { it.name != ".passwords.json" && !(clean.isBlank() && it.name == ".trash") }
.sortedWith(compareBy<ChildDoc> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.getDefault()) })
.map { child ->
FileEntry(
name = child.name,
isDirectory = child.isDirectory,
size = if (child.isDirectory) 0L else child.size,
relativePath = joinPath(clean, child.name),
modifiedAt = child.modified
)
}
.toList()
}

private data class ChildDoc(
val uri: Uri,
val name: String,
val isDirectory: Boolean,
val size: Long,
val modified: Long
)

// Lists a directory's children reading all display columns in ONE query, so
// building a folder view costs a single SAF IPC regardless of item count.
private fun listChildDocs(dirUri: Uri): List<ChildDoc> {
val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, DocumentsContract.getDocumentId(dirUri))
val cursor = context.contentResolver.query(
childrenUri,
arrayOf(
DocumentsContract.Document.COLUMN_DOCUMENT_ID,
DocumentsContract.Document.COLUMN_DISPLAY_NAME,
DocumentsContract.Document.COLUMN_MIME_TYPE,
DocumentsContract.Document.COLUMN_SIZE,
DocumentsContract.Document.COLUMN_LAST_MODIFIED
),
null,
null,
null
) ?: return emptyList()
val docs = mutableListOf<ChildDoc>()
cursor.use {
val idIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
val nameIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
val mimeIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
val sizeIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
val modIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
while (it.moveToNext()) {
val docId = it.getString(idIdx) ?: continue
val name = it.getString(nameIdx) ?: continue
val isDir = it.getString(mimeIdx) == DocumentsContract.Document.MIME_TYPE_DIR
val size = if (sizeIdx >= 0 && !it.isNull(sizeIdx)) it.getLong(sizeIdx) else 0L
val modified = if (modIdx >= 0 && !it.isNull(modIdx)) it.getLong(modIdx) else 0L
docs.add(ChildDoc(DocumentsContract.buildDocumentUriUsingTree(dirUri, docId), name, isDir, size, modified))
}
}
return docs
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
listChildDocs(dirUri).sortedBy { it.name.lowercase(Locale.getDefault()) }.forEach { child ->
val childName = child.name
val nextPrefix = "$prefix/$childName"
val rel = joinPath(baseDir, nextPrefix)
if (childName == ".passwords.json" || skip(rel)) return@forEach
if (child.isDirectory) {
collectDirSaf(child.uri, nextPrefix, baseDir, out, skip)
} else {
out.add(ZipSource(nextPrefix) {
context.contentResolver.openInputStream(child.uri) ?: ByteArrayInputStream(ByteArray(0))
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

private fun findChild(parentUri: Uri, name: String): Uri? {
val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, DocumentsContract.getDocumentId(parentUri))
val cursor = context.contentResolver.query(
childrenUri,
arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
null,
null,
null
) ?: return null
cursor.use {
val idIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
val nameIdx = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
while (it.moveToNext()) {
if (it.getString(nameIdx) == name) {
return DocumentsContract.buildDocumentUriUsingTree(parentUri, it.getString(idIdx))
}
}
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

fun contentDisposition(name: String): String = disposition("attachment", name)

fun contentDispositionInline(name: String): String = disposition("inline", name)

private fun disposition(type: String, name: String): String {
val asciiFallback = name.map { if (it.code in 32..126) it else '_' }.joinToString("")
val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
return "$type; filename=\"$asciiFallback\"; filename*=UTF-8''$encoded"
}

fun randomHex16(): String {
val bytes = ByteArray(16)
SecureRandom().nextBytes(bytes)
return bytes.joinToString("") { "%02x".format(it) }
}
}
