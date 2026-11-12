package dev.mirutime.app.features.opds.data

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Login/password pairs for OPDS catalogs, keyed by catalog id.
 *
 * shortcut: app-sandbox plaintext (noBackupFilesDir), wrap in Keystore AES-GCM if the threat model changes.
 */
class OpdsCredentialStore(private val file: File) {
    // org.json cannot run in JVM unit tests (the android.jar stub returns
    // null under returnDefaultValues); kotlinx.serialization is already an app
    // dependency and behaves the same on both.
    @Serializable
    data class Credentials(val login: String, val password: String)

    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun read(): Map<String, Credentials> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Credentials>>(text) }
            .getOrDefault(emptyMap())
    }

    @Synchronized
    fun put(id: String, login: String, password: String) {
        val all = read().toMutableMap()
        all[id] = Credentials(login, password)
        write(all)
    }

    fun get(id: String): Credentials? = read()[id]

    @Synchronized
    fun remove(id: String) {
        val all = read().toMutableMap()
        if (all.remove(id) == null) return
        write(all)
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun write(all: Map<String, Credentials>) {
        val text = json.encodeToString(all)
        file.parentFile?.mkdirs()
        // Temp + rename: a process death mid-write must not leave a truncated
        // JSON file that reads as "no saved credentials".
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) file.writeText(text)
        temp.delete()
    }

    companion object {
        private const val FILE_NAME = "opds_credentials.json"

        /** Excluded from Auto Backup and from the app's own export by location. */
        fun android(context: Context): OpdsCredentialStore =
            OpdsCredentialStore(File(context.noBackupFilesDir, FILE_NAME))
    }
}
