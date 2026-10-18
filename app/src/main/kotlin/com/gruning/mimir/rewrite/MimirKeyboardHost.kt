package com.gruning.mimir.rewrite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusBinding
import com.gruning.mimir.rewrite.core.input.rememberMimirKeyboardFocusBinding
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardController
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecoration
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecorator

data class MimirKeyboardHost(
    val controller: MimirKeyboardController,
    val keyDecorator: MimirKeyboardKeyDecorator,
    val binding: MimirKeyboardFocusBinding,
    /** [MimirOverlayKind.name] for app-shell overlays; null for ownerless surfaces such as onboarding. */
    val owner: String? = null,
)

@Composable
internal fun rememberMimirKeyboardHost(
    authority: FocusAuthority,
    active: Boolean,
    owner: MimirOverlayKind? = null,
): MimirKeyboardHost? {
    val binding = rememberMimirKeyboardFocusBinding(authority, active) ?: return null
    val ownerTag = owner?.name
    return remember(binding, ownerTag) {
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
        MimirKeyboardHost(
            controller = controller,
            keyDecorator = keyDecorator,
            binding = binding,
            owner = ownerTag,
        )
    }
}
