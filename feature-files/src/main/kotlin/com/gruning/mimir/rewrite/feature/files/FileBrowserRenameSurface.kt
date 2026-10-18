package com.gruning.mimir.rewrite.feature.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.rememberMimirKeyboardFocusBinding
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardController
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecoration
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator

/**
 * **Rename's on-screen keyboard** (the name is typed on Mimir's own keyboard, the
 * one Library search uses, never a system IME dialog).
 *
 * [FileBrowserSurface] composes this inside a `MimirSheetHost` whose `visible` is
 * [FileBrowserController.renameOpen], so it slides in on the sheet tier exactly as the search keyboard does
 * and stays composed through its exit. Everything about the lifecycle is [FileBrowserSearchKeyboard]'s, for
 * the same reasons: the focus binding follows the flip (not the end of the fade), and
 * [FileBrowserController.attachRenameKeyboard] hands it down so `dispatch` can route to it and so a host
 * teardown can `exit()` it defensively.
 *
 * **Done commits the name; B abandons it.** A name that breaks a rule ([RenameProblem]) keeps the keyboard
 * up with the reason on the line above it, drawn from the first keystroke that breaks it, so the player
 * learns why before pressing Done rather than after.
 */
@Composable
internal fun FileBrowserRenameKeyboard(
    controller: FileBrowserController,
    authority: FocusAuthority,
    modifier: Modifier = Modifier,
) {
    val binding = rememberMimirKeyboardFocusBinding(authority, active = controller.renameOpen)
    DisposableEffect(controller, binding) {
        controller.attachRenameKeyboard(binding)
        onDispose { controller.attachRenameKeyboard(null) }
    }
    val keyboardController = remember(binding) {
        binding?.let { open ->
            MimirKeyboardController(
                onEnter = { open.enter() },
                onEnterAt = { open.enter(it) },
                onExit = open::exit,
                onKeysChanged = open::reconcileFocus,
                onShoulderActionsChanged = open::registerShoulderActions,
                onRowLayoutChanged = open::registerRowLayout,
            )
        }
    }
    val keyDecorator: MimirKeyboardKeyDecorator? = remember(binding) {
        binding?.let { open ->
            val decorator: MimirKeyboardKeyDecorator = { id, activate ->
                val (keyModifier, focused) = open.decorateKey(id.value, activate)
                MimirKeyboardKeyDecoration(keyModifier, focused)
            }
            decorator
        }
    }
    val palette = LocalMimirPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
        controller.renameProblem?.let { problem ->
            Text(
                text = problem.sentence,
                fontSize = 12.sp,
                color = palette.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 6.dp),
            )
        }
        MimirKeyboard(
            value = controller.renameText,
            onValueChange = controller::setRenameText,
            onDone = controller::confirmRename,
            controller = keyboardController,
            keyDecorator = keyDecorator,
            targetLabel = "Rename ${controller.renameOriginal}",
        )
    }
}
