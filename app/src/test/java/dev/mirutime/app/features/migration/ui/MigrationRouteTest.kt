package dev.mirutime.app.features.migration.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MigrationRouteTest {
    @Test
    fun `full screen entry is the standalone migration route`() {
        assertEquals("migration", MigrationRoutes.Screen)
        assertFalse(
            "The Book page opens the personal sheet, not this route",
            MigrationRoutes.Screen.startsWith("book/"),
        )
    }
}
