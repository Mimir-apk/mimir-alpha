package com.gruning.mimir.tweaker

import java.nio.charset.StandardCharsets

internal data class GameModeFpsPackageIdentity(
    val packageName: String,
    val userId: Int,
    val uid: Int,
    val versionCode: Long,
    val signingCertificateSha256: String,
) {
    init {
        require(FPS_PACKAGE_PATTERN.matches(packageName))
        require(userId >= 0 && uid >= 0 && versionCode >= 0L)
        require(FPS_SHA256_PATTERN.matches(signingCertificateSha256))
    }
}

internal data class GameModeModesReadback(
    val packageName: String,
    val currentMode: String,
    val availableModes: Set<String>,
)

internal sealed interface GameModeConfigReadback {
    val packageName: String

    data class Baseline(override val packageName: String) : GameModeConfigReadback

    data class FpsOverride(
        override val packageName: String,
        val fps: Int,
    ) : GameModeConfigReadback
}

internal data class GameModeFpsReadback(
    val modes: GameModeModesReadback,
    val config: GameModeConfigReadback,
) {
    init {
        require(modes.packageName == config.packageName)
    }

    fun isExactBaseline(): Boolean =
        modes.currentMode == GAME_MODE_STANDARD && config is GameModeConfigReadback.Baseline

    fun isExactTarget(fps: Int): Boolean =
        modes.currentMode == GAME_MODE_CUSTOM &&
            (config as? GameModeConfigReadback.FpsOverride)?.fps == fps
}

internal fun parseGameModeModesReadback(output: String?, expectedPackage: String): GameModeModesReadback? {
    if (!FPS_PACKAGE_PATTERN.matches(expectedPackage)) return null
    val match = FPS_MODES_OUTPUT.matchEntire(output.orEmpty().trim()) ?: return null
    if (match.groupValues[1] != expectedPackage) return null
    val current = match.groupValues[2]
    val available = match.groupValues[3].split(',').map(String::trim)
    if (available.isEmpty() || available.any { it !in GAME_MODE_NAMES }) return null
    if (available.distinct().size != available.size || current !in GAME_MODE_NAMES) return null
    return GameModeModesReadback(expectedPackage, current, available.toSet())
}

internal fun parseGameModeConfigReadback(output: String?, expectedPackage: String): GameModeConfigReadback? {
    if (!FPS_PACKAGE_PATTERN.matches(expectedPackage)) return null
    val normalized = output.orEmpty()
        .replace("\r\n", "\n")
        .lines()
        .joinToString("\n", transform = String::trim)
        .trim()
    if (normalized == "No interventions found for $expectedPackage" ||
        normalized == "$expectedPackage interventions:\nNo intervention found for package $expectedPackage" ||
        normalized == "$expectedPackage interventions:\n[Name:$expectedPackage Modes: {}]"
    ) {
        return GameModeConfigReadback.Baseline(expectedPackage)
    }
    val match = FPS_CONFIG_OUTPUT.matchEntire(normalized) ?: return null
    if (match.groupValues[1] != expectedPackage || match.groupValues[2] != expectedPackage) return null
    val scaling = match.groupValues[3].toFloatOrNull() ?: return null
    val useAngle = match.groupValues[4].toBooleanStrictOrNull() ?: return null
    val fps = match.groupValues[5].toIntOrNull() ?: return null
    val loadingBoost = match.groupValues[6].toIntOrNull() ?: return null
    if (scaling != -1f || useAngle || fps !in MIN_GAME_MODE_FPS..MAX_GAME_MODE_FPS || loadingBoost != -1) {
        return null
    }
    return GameModeConfigReadback.FpsOverride(expectedPackage, fps)
}

internal fun supportedGameModeFrameLimits(activeRefreshHz: Int): List<Int> {
    if (activeRefreshHz !in MIN_GAME_MODE_FPS..MAX_GAME_MODE_FPS) return emptyList()
    return GAME_MODE_FPS_CANDIDATES.filter { candidate ->
        candidate <= activeRefreshHz && activeRefreshHz % candidate == 0
    }
}

internal object GameModeFpsCommands {
    fun listModes(packageName: String): String? = command(packageName, "cmd game list-modes")

    fun listConfigs(packageName: String): String? = command(packageName, "cmd game list-configs")

    fun apply(packageName: String, userId: Int, fps: Int): String? {
        if (userId != 0 || fps !in GAME_MODE_FPS_CANDIDATES) return null
        return command(packageName, "cmd game set --user 0 --mode 4 --fps $fps")
    }

    fun reset(packageName: String, userId: Int): String? {
        if (userId != 0) return null
        return command(packageName, "cmd game reset --user 0")
    }

    fun standard(packageName: String, userId: Int): String? {
        if (userId != 0) return null
        return command(packageName, "cmd game mode --user 0 standard")
    }

    private fun command(packageName: String, prefix: String): String? {
        if (!FPS_PACKAGE_PATTERN.matches(packageName)) return null
        val command = "$prefix $packageName"
        return command.takeIf(::checkedPServerCommandFits)
    }
}

/** Mirrors PServerClient's exact wrapper with the longest nonce its generator can emit. */
internal fun checkedPServerCommandFits(command: String): Boolean {
    val nonce = "n${"9".repeat(19)}_${"a".repeat(8)}"
    val wrapped = "b=\$( ( $command ) 2>/dev/null);s=\$?;printf '${PServerClient.EXIT_MARKER_PREFIX}$nonce:%s:%s:%s\\n' \"\$s\" \"\${#b}\" \"\$b\""
    return wrapped.toByteArray(StandardCharsets.UTF_8).size <= PServerClient.MAX_CHECKED_COMMAND_BYTES
}

internal fun exactRepeatedFpsBaseline(
    first: GameModeFpsReadback?,
    second: GameModeFpsReadback?,
): GameModeFpsReadback? = first?.takeIf { it == second && it.isExactBaseline() }

private val FPS_PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
private val FPS_SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val FPS_MODES_OUTPUT = Regex(
    "^([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+) current mode: " +
        "(standard|performance|battery|custom), available game modes: " +
        "\\[([a-z]+(?:,[a-z]+)*)\\]$",
)
private val FPS_CONFIG_OUTPUT = Regex(
    "^([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+) interventions:\\n" +
        "\\[Name:([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+) Modes: " +
        "\\{4=\\[Game Mode:4,Scaling:([-0-9.]+),Use Angle:(true|false)," +
        "Fps:([0-9]+),Loading Boost Duration:(-?[0-9]+)\\]\\}\\]$",
)
private val GAME_MODE_NAMES = setOf("standard", "performance", "battery", "custom")
private const val GAME_MODE_STANDARD = "standard"
private const val GAME_MODE_CUSTOM = "custom"
private val GAME_MODE_FPS_CANDIDATES = listOf(30, 40, 60, 90, 120)
private const val MIN_GAME_MODE_FPS = 24
private const val MAX_GAME_MODE_FPS = 240
