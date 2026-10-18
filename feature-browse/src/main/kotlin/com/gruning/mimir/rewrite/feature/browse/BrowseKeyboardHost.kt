package com.gruning.mimir.rewrite.feature.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusBinding
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.dispatchMimirKeyboardInput
import com.gruning.mimir.rewrite.core.input.rememberMimirKeyboardFocusBinding
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardController
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecoration
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator

data class BrowseMimirKeyboardHost(
    val controller: MimirKeyboardController,
    val keyDecorator: MimirKeyboardKeyDecorator,
    val binding: MimirKeyboardFocusBinding,
)

@Composable
fun rememberBrowseMimirKeyboardHost(
    authority: FocusAuthority,
    active: Boolean,
): BrowseMimirKeyboardHost? {
    val binding = rememberMimirKeyboardFocusBinding(authority, active) ?: return null
    return remember(binding) {
        val controller = MimirKeyboardController(
            onEnter = { binding.enter() },
            onEnterAt = { binding.enter(it) },
            onExit = binding::exit,
            onKeysChanged = binding::reconcileFocus,
            onShoulderActionsChanged = binding::registerShoulderActions,
            onRowLayoutChanged = binding::registerRowLayout,
        )
        val keyDecorator: MimirKeyboardKeyDecorator = { id, activate ->
            val (modifier, focused) = binding.decorateKey(id.value, activate)
            MimirKeyboardKeyDecoration(modifier, focused)
        }
        BrowseMimirKeyboardHost(controller = controller, keyDecorator = keyDecorator, binding = binding)
    }
}

fun dispatchBrowseMimirKeyboard(
    authority: FocusAuthority,
    host: BrowseMimirKeyboardHost?,
    action: ControllerAction,
    onDismiss: () -> Unit,
): Boolean {
    val binding = host?.binding ?: return false
    return dispatchMimirKeyboardInput(
        authority = authority,
        scope = MimirKeyboardFocusScope,
        action = action,
        binding = binding,
        onDismiss = onDismiss,
    )
}
