package com.lanshare.app

import org.json.JSONObject
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PasswordManager(private val storage: StorageAccess) {
    private val mutex = Mutex()

    suspend fun isProtected(relativePath: String): Boolean = mutex.withLock {
        val db = loadDb()
        db.has(normalize(relativePath))
    }

    suspend fun protectedFiles(): Set<String> = mutex.withLock {
        val db = loadDb()
        db.keys().asSequence().toSet()
    }

    suspend fun setPassword(relativePath: String, password: String) = mutex.withLock {
        val clean = normalize(relativePath)
        val db = loadDb()
        if (password.isBlank()) {
            db.remove(clean)
        } else {
            val salt = FileUtils.randomHex16()
            val hash = sha256Hex(salt + password)
            val entry = JSONObject()
            entry.put("salt", salt)
            entry.put("hash", hash)
            db.put(clean, entry)
        }
        saveDb(db)
    }

    suspend fun verify(relativePath: String, password: String?): Boolean = mutex.withLock {
        val clean = normalize(relativePath)
        val db = loadDb()
        if (!db.has(clean)) return@withLock true
        if (password.isNullOrEmpty()) return@withLock false
        val obj = db.optJSONObject(clean) ?: return@withLock false
        val salt = obj.optString("salt")
        val hash = obj.optString("hash")
        hash.equals(sha256Hex(salt + password), ignoreCase = true)
    }

    private fun loadDb(): JSONObject {
        val text = storage.readPasswordsJson()
        return try {
            JSONObject(text)
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    private fun saveDb(obj: JSONObject) {
        storage.writePasswordsJson(obj.toString())
    }

    private fun normalize(path: String): String = path.trim().trim('/')

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
