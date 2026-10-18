package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.BuildConfig

/**
 * The ScreenScraper SOFTWARE registration — the `devid`/`devpassword`/`softname` that ScreenScraper.fr
 * requires on EVERY API call to identify the calling app . This is
 * NOT the user's quota-bearing account (that is `ssid`/`sspassword`, entered at runtime in the connect
 * flow) and NOT a content secret — but it is still kept out of the repo: it is injected from the
 * untracked `local.properties` into [BuildConfig] at build time, with EMPTY defaults.
 *
 * When a checkout has no creds (a fresh clone, CI), [isConfigured] is false → the opt-in cover fetch is
 * simply unavailable and the app stays fully offline. The connect flow surfaces this honestly rather
 * than attempting a call that would fail. NEVER log these values.
 */
object ScreenScraperDevRegistration {

    val devId: String get() = BuildConfig.SCREENSCRAPER_DEV_ID
    val devPassword: String get() = BuildConfig.SCREENSCRAPER_DEV_PASSWORD

    /** Display name of the app as registered with ScreenScraper. Defaults to "MimirApk". */
    val softName: String get() = BuildConfig.SCREENSCRAPER_SOFTNAME.ifBlank { "MimirApk" }

    /** True when this build carries a usable software registration (both id + password present). */
    val isConfigured: Boolean get() = isConfigured(devId, devPassword)

    /** Pure predicate (unit-testable without the generated BuildConfig). */
    fun isConfigured(devId: String?, devPassword: String?): Boolean =
        !devId.isNullOrBlank() && !devPassword.isNullOrBlank()
}
