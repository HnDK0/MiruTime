package dev.mirutime.app.features.video.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRenderersFactoryTest {

    @Test
    fun `generic fingerprint is an emulator`() {
        assertTrue(
            isEmulatorDevice(
                fingerprint = "generic/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228840:userdebug/dev-keys",
                product = "emu64a",
                hardware = "cutf_cvm",
            ),
        )
    }

    @Test
    fun `sdk_gphone product is an emulator`() {
        assertTrue(
            isEmulatorDevice(
                fingerprint = "retail/fingerprint",
                product = "sdk_gphone64_arm64",
                hardware = "cutf_cvm",
            ),
        )
    }

    @Test
    fun `ranchu hardware is an emulator`() {
        assertTrue(
            isEmulatorDevice(
                fingerprint = "retail/fingerprint",
                product = "retail_product",
                hardware = "ranchu",
            ),
        )
    }

    @Test
    fun `retail device is not an emulator`() {
        assertFalse(
            isEmulatorDevice(
                fingerprint = "samsung/r0qxxx/r0q:13/TP1A.220624.014/S908BXXU2AVD1:user/release-keys",
                product = "r0qxxx",
                hardware = "qcom",
            ),
        )
    }

    @Test
    fun `emulator filter drops goldfish decoders case-insensitively`() {
        val names = listOf(
            "c2.goldfish.h264.decoder",
            "c2.android.avc.decoder",
            "C2.GOLDFISH.hevc.decoder",
            "c2.qti.avc.decoder",
        )

        assertEquals(
            listOf("c2.android.avc.decoder", "c2.qti.avc.decoder"),
            filterEmulatorDecoders(names, isEmulator = true),
        )
    }

    @Test
    fun `real device keeps the decoder list untouched`() {
        val names = listOf("c2.goldfish.h264.decoder", "c2.android.avc.decoder")

        assertEquals(names, filterEmulatorDecoders(names, isEmulator = false))
    }

    @Test
    fun `all-goldfish list falls back to the unfiltered list`() {
        val names = listOf("c2.goldfish.h264.decoder")

        assertEquals(names, filterEmulatorDecoders(names, isEmulator = true))
    }
}
