package com.gruning.mimir.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.MimirFonts
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

internal const val DiagnosticsBrandEyebrowCopy = "MIMIR"
internal const val DiagnosticsPostureEyebrowCopy = "POSTURE"
internal const val DiagnosticsTechnicalDetailsEyebrowCopy = "TECHNICAL DETAILS"

/**
 * M1 HOME — the Diagnostics / posture-readout screen.
 *
 * The honest posture readout, restructured per policy R4 (2026-07-09): the POSTURE truth (grounded
 * in the ACTUAL declared permissions read from PackageManager, not a hardcoded string) stays front and
 * center; the developer internals (app id, version, raw permission list) fold behind a collapsed
 * "Technical details" disclosure; the codename module list and the Shelf Lab prototype entry are REMOVED
 * from the user-facing surface entirely.
 *
 * @param onOpenSettings navigate to the Settings surface (feature-settings).
 * @param onOpenLibrary navigate to the Library surface (feature-library) — the console core.
 * @param onOpenBrowse navigate to Browse (feature-browse) — the hidden-games recover surface.
 */
@Composable
fun DiagnosticsScreen(
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenBrowse: () -> Unit,
) {
    val context = LocalContext.current
    val posture = rememberPosture(context)
    var techOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MimirColors.SurfaceBase)
            .verticalScroll(rememberScrollState())
            .padding(MimirSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
    ) {
        Text(DiagnosticsBrandEyebrowCopy, style = MimirType.RunicEyebrow, color = MimirColors.Gold)
        Text("Troubleshooting", style = MimirType.Title, color = MimirColors.TextPrimary)

        Spacer(Modifier.height(MimirSpacing.sm))

        PostureCard(posture)

        // Developer internals fold behind a collapsed disclosure (policy R4): the posture TRUTH above
        // is for everyone; app ids and raw permission names are for the curious. The codename module list
        // and the Shelf Lab prototype row were removed from this surface entirely (same ruling).
        FactCard(title = DiagnosticsTechnicalDetailsEyebrowCopy) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MimirSpacing.radiusPill))
                    .clickableSafe { techOpen = !techOpen }
                    .padding(vertical = MimirSpacing.xs),
            ) {
                Text(
                    if (techOpen) "Hide the build and permission details" else "Show the build and permission details",
                    style = MimirType.RowSupport,
                    color = MimirColors.Gold,
                )
            }
            if (techOpen) {
                Fact("app id", posture.appId)
                Fact("version", posture.versionName)
                Fact("side-by-side", "installs alongside com.gruning.mimir (never collides)")
                Spacer(Modifier.height(MimirSpacing.xs))
                if (posture.declaredPermissions.isEmpty()) {
                    Fact("(none)", "baseline manifest declares zero permissions")
                } else {
                    posture.declaredPermissions.forEach { perm ->
                        // Each permission shows its honest PURPOSE. INTERNET is the metadata refresh; the
                        // WorkManager infra perms support it as background work; only a genuinely UNEXPECTED
                        // permission is flagged red.
                        val unexpected = perm in posture.unexpectedPermissions
                        val metadataScoped = perm == PostureReadout.PERMISSION_INTERNET
                        val nativeCompanion = perm in PostureReadout.NATIVE_COMPANION_PERMISSIONS
                        Fact(
                            key = perm.substringAfterLast('.'),
                            value = "${perm.substringAfterLast('.')} · ${posture.purposeOf(perm)}",
                            valueColor = when {
                                unexpected -> MimirColors.Danger
                                metadataScoped -> MimirColors.Gold
                                nativeCompanion -> MimirColors.Gold
                                else -> MimirColors.TextSecondary
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(MimirSpacing.sm))

        // The navigable actions on the M1 home: the real Compose surfaces.
        ActionRow(label = "Open Library", onClick = onOpenLibrary)
        ActionRow(label = "Open Settings", onClick = onOpenSettings)
        ActionRow(label = "Open Browse (hidden games)", onClick = onOpenBrowse)
    }
}

@Composable
private fun PostureCard(posture: PostureReadout) {
    val ok = posture.isPostureClean
    val statusColor = if (ok) MimirColors.SuccessGreen else MimirColors.Danger
    val statusText = when {
        !ok -> "Found a permission we didn’t expect — worth a look."
        posture.hasInternetPermission && posture.hasNativeCompanionPermission ->
            "local-first · metadata + local device access"
        posture.hasInternetPermission -> "local-first · metadata-scoped INTERNET"
        else -> "local-first · no network"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .background(MimirColors.SurfaceRaised)
            .border(1.dp, if (ok) MimirColors.Outline else MimirColors.Danger, RoundedCornerShape(MimirSpacing.radiusCard))
            .padding(MimirSpacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
            Text(DiagnosticsPostureEyebrowCopy, style = MimirType.RunicEyebrow, color = MimirColors.Gold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(MimirSpacing.radiusPill))
                        .background(statusColor.copy(alpha = 0.16f))
                        .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.xs),
                ) {
                    Text(statusText, style = MimirType.Pill, color = statusColor)
                }
            }
            Text(
                "Local-first. Network use is metadata only — DAT/ID catalogs (checksums + names), never " +
                    "game content, BIOS, or firmware. Mimir button and launch-check permissions are local device " +
                    "controls. No acquisition, ever. Read from the app’s actual declared " +
                    "permissions, not a hardcoded label.",
                style = MimirType.RowSupport,
                color = MimirColors.TextMuted,
            )
        }
    }
}

@Composable
private fun FactCard(title: String, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .background(MimirColors.SurfaceRaised)
            .border(1.dp, MimirColors.Outline, RoundedCornerShape(MimirSpacing.radiusCard))
            .padding(MimirSpacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
            Text(title, style = MimirType.RunicEyebrow, color = MimirColors.Gold)
            Spacer(Modifier.height(MimirSpacing.xs))
            content()
        }
    }
}

@Composable
private fun Fact(key: String, value: String, valueColor: androidx.compose.ui.graphics.Color = MimirColors.TextSecondary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
        Text(
            key,
            // The product's own mono face, not the platform's — these keys sit in a column beside values
            // and must align, but they should not look like they came from a different app.
            style = MimirType.RowSupport.copy(fontFamily = MimirFonts.Mono),
            color = MimirColors.TextMuted,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MimirType.RowSupport, color = valueColor)
    }
}

/**
 * One navigation action of the diagnostics screen: an etched chip centred in a full-width 48 dp hit box.
 * The three actions are peers, so each is the plain chip. This screen is touch-only (no focus authority), so the chip wears no ring.
 */
@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    val palette = LocalMimirPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MimirSpacing.minTarget)
            .clickableSafe(onClick),
        contentAlignment = Alignment.Center,
    ) {
        MimirEtchedChipPlate(modifier = Modifier.testTag(etchedChipPlateTag("diagnostics", label)), palette = palette) {
            MimirChromeText(label, style = MimirEtchedChipLabelStyle, color = palette.etchedChipInk(), maxLines = 1, softWrap = false)
        }
    }
}
