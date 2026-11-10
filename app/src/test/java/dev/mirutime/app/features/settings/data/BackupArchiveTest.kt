package dev.mirutime.app.features.settings.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveTest {

    private val dbBytes = "db".encodeToByteArray()

    private fun databaseEntry() = BackupEntry(
        name = DATABASE_ENTRY,
        size = dbBytes.size.toLong(),
        sha256 = sha256Hex(dbBytes),
    )

    private fun manifest(
        format: Int,
        pluginKv: BackupEntry? = null,
        localSources: BackupEntry? = null,
    ) = BackupManifest(
        formatVersion = format,
        appVersion = "test",
        createdAt = 1L,
        settingsIncluded = pluginKv != null || localSources != null,
        imagesIncluded = false,
        pluginsIncluded = pluginKv != null,
        readerIncluded = false,
        database = databaseEntry(),
        pluginKv = pluginKv,
        localSources = localSources,
        counts = BackupCounts(localBooks = 0, localFiles = 0),
    )

    private fun entry(name: String, bytes: ByteArray) = BackupEntry(
        name = name,
        size = bytes.size.toLong(),
        sha256 = sha256Hex(bytes),
    )

    private fun archive(
        value: BackupManifest,
        payload: Map<String, ByteArray> = mapOf(DATABASE_ENTRY to dbBytes),
    ): File {
        val file = File(tempDir(), "backup-${value.formatVersion}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(BackupJson.encodeToString(BackupManifest.serializer(), value).encodeToByteArray())
            zip.closeEntry()
            payload.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun assertInvalid(file: File) {
        assertNotNull(
            "expected validation failure",
            runCatching { validateBackupArchive(file) }.exceptionOrNull(),
        )
    }

    @Test
    fun `old formats without the new entries stay valid`() {
        for (format in 1..3) {
            validateBackupArchive(archive(manifest(format)))
        }
    }

    @Test
    fun `v4 archive with plugin kv and local sources is valid`() {
        val kv = "{\"entries\":{\"repo:url\":\"https://example.test\"}}".encodeToByteArray()
        val sources = "{\"books\":[\"content://tree/books\"]}".encodeToByteArray()
        val value = manifest(
            format = 4,
            pluginKv = entry(PLUGIN_KV_ENTRY, kv),
            localSources = entry(LOCAL_SOURCES_ENTRY, sources),
        )

        validateBackupArchive(
            archive(value, payload = mapOf(
                DATABASE_ENTRY to dbBytes,
                PLUGIN_KV_ENTRY to kv,
                LOCAL_SOURCES_ENTRY to sources,
            )),
        )
    }

    @Test
    fun `missing new entry is rejected`() {
        val kv = "{}".encodeToByteArray()
        val value = manifest(
            format = 4,
            pluginKv = entry(PLUGIN_KV_ENTRY, kv),
        )

        assertInvalid(archive(value))
    }

    @Test
    fun `extra new entry is rejected`() {
        val value = manifest(format = 4)
        assertInvalid(
            archive(value, payload = mapOf(
                DATABASE_ENTRY to dbBytes,
                PLUGIN_KV_ENTRY to "{}".encodeToByteArray(),
            )),
        )
    }

    @Test
    fun `checksum mismatch is rejected`() {
        val kv = "{}".encodeToByteArray()
        val value = manifest(
            format = 4,
            pluginKv = entry(PLUGIN_KV_ENTRY, "other".encodeToByteArray()),
        )

        assertInvalid(
            archive(value, payload = mapOf(
                DATABASE_ENTRY to dbBytes,
                PLUGIN_KV_ENTRY to kv,
            )),
        )
    }

    @Test
    fun `oversize new entry is rejected`() {
        val bytes = ByteArray((MAX_ARCHIVE_PLUGIN_KV_BYTES + 1).toInt())
        val value = manifest(
            format = 4,
            pluginKv = entry(PLUGIN_KV_ENTRY, bytes),
        )

        assertInvalid(
            archive(value, payload = mapOf(
                DATABASE_ENTRY to dbBytes,
                PLUGIN_KV_ENTRY to bytes,
            )),
        )
    }

    @Test
    fun `manifest is the first entry`() {
        val file = File(tempDir(), "bad-order.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
            zip.write(dbBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(BackupJson.encodeToString(BackupManifest.serializer(), manifest(4)).encodeToByteArray())
            zip.closeEntry()
        }

        assertTrue(runCatching { validateBackupArchive(file) }.isFailure)
    }

    private fun tempDir(): File =
        kotlin.io.path.createTempDirectory("miru-backup-test").toFile()
}
