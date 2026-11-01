package dev.mirutime.app.features.settings.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryOptimizationStatusTest {
    @Test
    fun `ignoring is always the truthful ignored state`() {
        assertEquals(
            BatteryOptimizationState.Ignored,
            BatteryOptimizationState.map(ignoring = true, actionAvailable = true),
        )
        assertEquals(
            BatteryOptimizationState.Ignored,
            BatteryOptimizationState.map(ignoring = true, actionAvailable = false),
        )
    }

    @Test
    fun `no system action is unsupported rather than a false success`() {
        assertEquals(
            BatteryOptimizationState.Unsupported,
            BatteryOptimizationState.map(ignoring = false, actionAvailable = false),
        )
        assertEquals(
            BatteryOptimizationState.Optimized,
            BatteryOptimizationState.map(ignoring = false, actionAvailable = true),
        )
    }

    @Test
    fun `status exposes the same mapping`() {
        val optimized = BatteryOptimizationStatus(
            ignoring = false,
            requestAvailable = false,
            settingsAvailable = true,
        )
        val statusOnly = BatteryOptimizationStatus(
            ignoring = false,
            requestAvailable = false,
            settingsAvailable = false,
        )

        assertEquals(BatteryOptimizationState.Optimized, optimized.state)
        assertEquals(BatteryOptimizationState.Unsupported, statusOnly.state)
    }
}
