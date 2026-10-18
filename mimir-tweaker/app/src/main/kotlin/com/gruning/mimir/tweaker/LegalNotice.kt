package com.gruning.mimir.tweaker

import android.content.Context

/**
 * Where the source code of Mimir and the Mimir Tweaker is published. GPL-3.0 section 6 asks for the corresponding source to be
 * offered with the app, and the notice on the main screen shows this address.
 *
 * The Tweaker is a separate Gradle build and cannot read Mimir's constant,
 * so this is its own copy of `MimirSourceUrl` (feature-settings `LegalNotice.kt`); the app module's `LegalNoticeFilesTest` fails if
 * the two differ.
 */
internal const val MimirSourceUrl = "https://github.com/Mimir-apk/mimir-alpha"

/** The Tweaker's notice and license, packaged as assets (copies of `mimir-tweaker/NOTICE.md` and the root `LICENSE`). */
internal const val LegalNoticeAssetPath = "legal/NOTICE.md"
internal const val LegalLicenseAssetPath = "legal/LICENSE"

/** The text of one packaged legal asset, or null when it cannot be read. Reads a file: it is small, and the caller is a button press. */
internal fun readLegalAsset(context: Context, path: String): String? =
    runCatching { context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
