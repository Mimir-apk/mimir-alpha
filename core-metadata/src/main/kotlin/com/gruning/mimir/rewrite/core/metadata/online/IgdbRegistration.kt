package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.BuildConfig

/**
 * The IGDB/Twitch app registration — a Twitch
 * developer console Client ID + secret, mirroring [ScreenScraperDevRegistration]'s shape exactly: an
 * APP registration, not a personal key, injected from the untracked `local.properties` into
 * [BuildConfig] at build time with EMPTY defaults.
 *
 * When a checkout has no creds (a fresh clone, CI, or a local build before keys are configured),
 * [isConfigured] is false → [IgdbMetadataSource] returns `GateClosed` before any request is built. NEVER
 * log [clientId] or [clientSecret].
 */
object IgdbRegistration {

    val clientId: String get() = BuildConfig.IGDB_TWITCH_CLIENT_ID
    val clientSecret: String get() = BuildConfig.IGDB_TWITCH_CLIENT_SECRET

    /** True when this build carries a usable IGDB/Twitch registration (both id + secret present). */
    val isConfigured: Boolean get() = isConfigured(clientId, clientSecret)

    /** Pure predicate (unit-testable without the generated BuildConfig). */
    fun isConfigured(clientId: String?, clientSecret: String?): Boolean =
        !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()
}
