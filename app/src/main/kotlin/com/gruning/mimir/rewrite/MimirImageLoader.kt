package com.gruning.mimir.rewrite

import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import java.io.File
import java.net.URI
import java.net.URL

/**
 * The app's LOCAL-ONLY Coil [ImageLoader]. Explicit provider clients may retrieve media under their
 * own consent and bounded transport rules, but they stage it locally before presentation. This loader
 * keeps Coil's default local decoders (`content://` + `file://`) and installs
 * [LocalOnlyInterceptor], which HARD-BLOCKS any http/https request before a fetch is attempted — so a
 * stray remote art URL can never put a byte on the wire, by construction, regardless of caller. Installed
 * as the process singleton in [MainActivity.onCreate], so every `SubcomposeAsyncImage` uses it.
 *
 * [CancellationQuietDecoderFactory] wraps Coil's own bitmap decoder so that a CANCELLED decode no longer prints a
 * platform stack trace to System.err (QA 2026-09-11 D17); every real decode failure keeps today's path.
 */
fun buildLocalOnlyImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context.applicationContext)
        .components {
            add(LocalOnlyInterceptor())
            add(CancellationQuietDecoderFactory())
        }
        // PERF-B: an EXPLICIT memory-cache budget. With covers decoded at the shared cap
        // (MimirCoverDecodeCapPx ≈ 2-3 MB each) this comfortably holds the whole hot set
        // (home window + gamelist window + favorites), so navigate-back re-paints from cache
        // instead of re-decoding — the "asset resettlement" flash. Default was 25%.
        .memoryCache {
            coil.memory.MemoryCache.Builder(context.applicationContext)
                .maxSizePercent(0.30)
                .build()
        }
        .build()

/**
 * Refuses every http/https image request with an error result. Provider consent authorizes only the
 * explicit bounded fetch clients, which validate and stage media locally before UI code receives it;
 * it never authorizes composition, prewarming, or navigation to fetch a remote model through Coil.
 */
class LocalOnlyInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        return when (mimirImageRequestDecision(chain.request.data)) {
            MimirImageRequestDecision.BLOCK_REMOTE -> ErrorResult(
                drawable = null,
                request = chain.request,
                throwable = IllegalStateException("Mimir blocked a remote renderer image request."),
            )
            MimirImageRequestDecision.PROCEED_LOCAL -> chain.proceed(chain.request)
        }
    }
}

internal enum class MimirImageRequestDecision {
    BLOCK_REMOTE,
    PROCEED_LOCAL,
}

internal fun mimirImageRequestDecision(data: Any?): MimirImageRequestDecision {
    val scheme = when (data) {
        is File -> null
        is Number -> null
        is Uri -> data.scheme
        is URI -> data.scheme
        is URL -> data.protocol
        is CharSequence -> mimirImageSchemeFromText(data.toString())
        null -> null
        else -> runCatching { mimirImageSchemeFromText(data.toString()) }.getOrNull()
    }
    val remote = scheme.equals("http", ignoreCase = true) ||
        scheme.equals("https", ignoreCase = true)
    return if (remote) {
        MimirImageRequestDecision.BLOCK_REMOTE
    } else {
        MimirImageRequestDecision.PROCEED_LOCAL
    }
}

private fun mimirImageSchemeFromText(value: String): String? {
    val text = value.trimStart()
    val separator = text.indexOf(':')
    if (separator <= 0) return null
    val candidate = text.substring(0, separator)
    if (!candidate.first().isLetter() ||
        candidate.any { character ->
            !character.isLetterOrDigit() && character != '+' && character != '-' && character != '.'
        }
    ) {
        return null
    }
    return candidate
}
