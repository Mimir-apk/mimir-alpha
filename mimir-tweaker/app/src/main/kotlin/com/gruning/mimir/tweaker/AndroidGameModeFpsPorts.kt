package com.gruning.mimir.tweaker

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.UserManager
import android.view.Display
import java.security.MessageDigest
import kotlin.math.roundToInt

internal class AndroidGameModeFpsPackageIdentityPort(
    context: Context,
) : GameModeFpsPackageIdentityPort {
    private val appContext = context.applicationContext

    override fun exactGame(packageName: String): GameModeFpsPackageIdentity? = runCatching {
        if (appContext.getSystemService(UserManager::class.java)?.isSystemUser != true) return null
        val userId = 0
        val packageInfo = appContext.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val applicationInfo = packageInfo.applicationInfo ?: return null
        if (applicationInfo.category != ApplicationInfo.CATEGORY_GAME) return null
        val signers = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        if (signers.isEmpty()) return null
        GameModeFpsPackageIdentity(
            packageName = packageName,
            userId = userId,
            uid = applicationInfo.uid,
            versionCode = packageInfo.longVersionCode,
            signingCertificateSha256 = canonicalFpsSigningDigest(signers.map { it.toByteArray() }),
        )
    }.getOrNull()
}

internal class PServerGameModeFpsControlPort(
    private val pServer: PServerClient,
) : GameModeFpsControlPort {
    override val available: Boolean get() = pServer.isAvailable

    override fun observe(packageName: String): GameModeFpsReadback? {
        val modesCommand = GameModeFpsCommands.listModes(packageName) ?: return null
        val configsCommand = GameModeFpsCommands.listConfigs(packageName) ?: return null
        val modes = pServer.execute(modesCommand).getOrNull()
            ?.let { parseGameModeModesReadback(it, packageName) }
            ?: return null
        val config = pServer.execute(configsCommand).getOrNull()
            ?.let { parseGameModeConfigReadback(it, packageName) }
            ?: return null
        return GameModeFpsReadback(modes, config)
    }

    override fun executeChecked(command: String): CheckedCommandResult = pServer.executeChecked(command)
}

internal class AndroidActiveRefreshRatePort(
    context: Context,
) : ActiveRefreshRatePort {
    private val displayManager = context.applicationContext.getSystemService(DisplayManager::class.java)

    override fun activeRefreshHz(): Int? = displayManager
        ?.getDisplay(Display.DEFAULT_DISPLAY)
        ?.refreshRate
        ?.takeIf { it.isFinite() && it > 0f }
        ?.roundToInt()
        ?.takeIf { it in 24..240 }
}

internal fun canonicalFpsSigningDigest(certificates: List<ByteArray>): String {
    if (certificates.isEmpty()) return ""
    val canonical = certificates.map { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }.sorted().joinToString(":")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
