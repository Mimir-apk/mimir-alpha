package com.gruning.mimir.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailure
import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailureReason
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipPlate
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing

internal const val DatabaseRecoveryEyebrow = "ᛉ · LIBRARY PROTECTED"
internal const val DatabaseRecoveryTitle = "THIS BUILD CANNOT OPEN YOUR LIBRARY"
internal const val DatabaseRecoveryInstruction =
    "INSTALL A NEWER MIMIR BUILD. DO NOT CLEAR APP STORAGE."
internal const val DatabaseRecoveryClose = "CLOSE MIMIR"

private val DatabaseRecoveryCloseKey = FocusKey("database-recovery/close")

internal object DatabaseStartupGateState {
    @Volatile
    var failure: DatabaseOpenFailure? = null
}

internal enum class DatabaseRecoveryCommand {
    CLOSE,
    CONSUME,
}

internal fun databaseRecoveryCommand(action: ControllerAction): DatabaseRecoveryCommand =
    when (action) {
        ControllerAction.CONFIRM,
        ControllerAction.CANCEL,
        ControllerAction.SYSTEM_BACK,
        ControllerAction.SYSTEM_HOME,
        -> DatabaseRecoveryCommand.CLOSE
        else -> DatabaseRecoveryCommand.CONSUME
    }

internal fun databaseRecoveryDetail(failure: DatabaseOpenFailure): String =
    when (failure.reason) {
        DatabaseOpenFailureReason.NEWER_SCHEMA ->
            "YOUR LIBRARY USES DATABASE ${failure.foundSchemaVersion}. " +
                "THIS BUILD SUPPORTS DATABASE ${failure.supportedSchemaVersion}. NOTHING WAS CHANGED."
        DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE ->
            "MIMIR COULDN’T PROVE EVERY LEGACY FILE LOCATION IN DATABASE " +
                "${failure.foundSchemaVersion}. NOTHING WAS CHANGED."
    }

internal fun databaseRecoveryInstruction(failure: DatabaseOpenFailure): String =
    when (failure.reason) {
        DatabaseOpenFailureReason.NEWER_SCHEMA -> DatabaseRecoveryInstruction
        DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE ->
            "CLOSE MIMIR. KEEP APP STORAGE INTACT."
    }

internal fun databaseRecoveryAllowsRuntimeIntents(failure: DatabaseOpenFailure?): Boolean =
    failure == null

/**
 * Close-only startup refusal for a database written by a newer Mimir build.
 *
 * There is deliberately no reset, clear, export, rename, or retry affordance. The screen exists to
 * keep the owner's bytes safe and direct them to a compatible binary, not to guess at recovery.
 */
@Composable
internal fun DatabaseRecoveryGate(
    failure: DatabaseOpenFailure,
    themeId: MimirThemeId,
    authority: FocusAuthority,
    onClose: () -> Unit,
) {
    MimirTheme(themeId) {
        val palette = LocalMimirPalette.current
        val focusState by authority.state.collectAsState()
        val closeFocused =
            focusState.scope == FocusScope.Root && focusState.focused == DatabaseRecoveryCloseKey

        LaunchedEffect(authority) {
            // Registration occurs during layout. Request on the following frame so focus never appears
            // first on a phantom platform target.
            withFrameNanos { }
            authority.requestFocus(DatabaseRecoveryCloseKey)
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = palette.surfaceBase,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 620.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(28.dp))
                        .background(palette.surfaceRaised)
                        .border(1.dp, palette.outline, RoundedCornerShape(28.dp))
                        .padding(horizontal = 30.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    MimirChromeText(
                        text = "Library protected",
                        visualOverride = DatabaseRecoveryEyebrow,
                        style = MimirTypeScale.RunicEyebrow,
                        color = palette.accentBright,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    MimirChromeText(
                        text = DatabaseRecoveryTitle,
                        style = MimirTypeScale.TitleCaps,
                        color = palette.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = databaseRecoveryDetail(failure),
                        style = MimirTypeScale.Support,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = databaseRecoveryInstruction(failure),
                        style = MimirTypeScale.MenuAction,
                        color = palette.warning,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    // An etched chip inside a 48 dp hit box. The gate has one
                    // answer, so it is the plain chip: the ring is the shared selection ring on the PLATE itself, with no inset and BEFORE the surface, so it is the plate's one edge.
                    Box(
                        modifier = Modifier
                            .heightIn(min = MimirSpacing.minTarget)
                            .widthIn(min = MimirSpacing.minTarget)
                            .mimirFocusable(
                                authority = authority,
                                key = DatabaseRecoveryCloseKey,
                                scope = FocusScope.Root,
                            )
                            .mimirActivatable(label = DatabaseRecoveryClose, authority = authority, key = DatabaseRecoveryCloseKey, onActivate = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        MimirEtchedChipPlate(
                            modifier = Modifier.testTag(etchedChipPlateTag("recovery", DatabaseRecoveryClose)).mimirSelectionRing(focused = closeFocused, identity = DatabaseRecoveryCloseKey, shape = MimirRingShape.Pill, edgeFlush = true),
                            focused = closeFocused,
                            palette = palette,
                            edgeFlush = true,
                        ) {
                            MimirChromeText(
                                text = DatabaseRecoveryClose,
                                style = MimirEtchedChipLabelStyle,
                                color = palette.etchedChipInk(),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
