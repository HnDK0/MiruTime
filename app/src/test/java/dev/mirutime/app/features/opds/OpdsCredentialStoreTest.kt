package dev.mirutime.app.features.opds

import dev.mirutime.app.features.opds.data.OpdsCredentialStore
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsCredentialStoreTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `round-trips login and password per catalog`() {
        val store = OpdsCredentialStore(file("credentials.json"))
        store.put("a", "user", "пароль")
        store.put("b", "other", "secret")

        assertEquals(
            OpdsCredentialStore.Credentials("user", "пароль"),
            store.get("a"),
        )
        assertEquals(setOf("a", "b"), store.read().keys)

        // A second store on the same file sees the persisted map.
        assertEquals("secret", OpdsCredentialStore(file("credentials.json")).get("b")?.password)
    }

    @Test
    fun `corrupt file reads as empty instead of throwing`() {
        val file = file("credentials.json")
        file.parentFile?.mkdirs()
        file.writeText("{not json")

        val store = OpdsCredentialStore(file)
        assertTrue(store.read().isEmpty())
        assertNull(store.get("a"))

        // A write after corruption recovers a valid file.
        store.put("a", "user", "pass")
        assertEquals("user", store.get("a")?.login)
    }

    @Test
    fun `remove drops only the chosen catalog and clear deletes the file`() {
        val file = file("credentials.json")
        val store = OpdsCredentialStore(file)
        store.put("a", "user", "pass")
        store.put("b", "other", "secret")

        store.remove("a")
        assertNull(store.get("a"))
        assertEquals("secret", store.get("b")?.password)

        store.clear()
        assertTrue(store.read().isEmpty())
    }

    private var dir: File? = null

    private fun file(name: String): File = File(dir(), name)

    private fun dir(): File = dir ?: Files.createTempDirectory("opds-credentials").toFile()
        .also {
            dir = it
            tempDirs += it
        }
}
