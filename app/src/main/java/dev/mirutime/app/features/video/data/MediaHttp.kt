package dev.mirutime.app.features.video.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * One OkHttp client for video download and the variant probe. Media is a
 * separate transport from the Ktor plugin client: media3 streams bytes and the
 * probe must not download a body.
 *
 * System DNS on purpose: a CDN may live on a host the plugin engine's SafeDns
 * would reject, and media has no CF solver by design. No callTimeout: a whole
 * download must not be cut (the probe adds its own 8 s per-call cap).
 *
 * Media3 snapshots request headers at enqueue, so a cookie obtained after a
 * download starts (e.g. a late CF clearance) is not picked up; restart the
 * download to apply it.
 */
internal object MediaHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}
