package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/**
 * The keyboard [MimirInputMethodService] shows when the Compose one cannot be built: plain framework views, no
 * Compose, no theme, no owners. Deliberately plain and deliberately small -- its one job is that a crash while
 * building the real keyboard never leaves the player without any keyboard, now that Mimir Keyboard may be the
 * system default.
 *
 * It types through the same service callbacks the real keyboard does ([onText], [onBackspace], [onEnter]) and
 * always carries a key to leave for another keyboard ([onSwitch]). Nothing here logs what is typed.
 */
internal class MimirImeFallbackKeyboard(
    context: Context,
    private val onText: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onEnter: () -> Unit,
    private val onSwitch: () -> Unit,
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        setBackgroundColor(Background)
        setPadding(Pad, Pad, Pad, Pad)
        Rows.forEach { letters -> addRow(letters.map { key(it.toString()) { onText(it.toString()) } }) }
        addRow(
            listOf(
                key("⌨", weight = 1.4f, onClick = onSwitch),
                key("@") { onText("@") },
                key(".") { onText(".") },
                key("/") { onText("/") },
                key("space", weight = 3f) { onText(" ") },
                key("⌫", weight = 1.4f, onClick = onBackspace),
                key("↵", weight = 1.4f, onClick = onEnter),
            ),
        )
    }

    /** The visible key captions in row order, for tests and for accessibility checks. */
    internal fun keyCaptions(): List<String> = buildList {
        for (r in 0 until childCount) {
            val row = getChildAt(r) as LinearLayout
            for (k in 0 until row.childCount) add((row.getChildAt(k) as Button).text.toString())
        }
    }

    internal fun press(caption: String): Boolean {
        for (r in 0 until childCount) {
            val row = getChildAt(r) as LinearLayout
            for (k in 0 until row.childCount) {
                val button = row.getChildAt(k) as Button
                if (button.text.toString() == caption) return button.performClick()
            }
        }
        return false
    }

    private fun addRow(keys: List<View>) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        keys.forEach(row::addView)
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, RowHeight))
    }

    private fun key(caption: String, weight: Float = 1f, onClick: () -> Unit): Button =
        Button(context).apply {
            text = caption
            contentDescription = when (caption) {
                "⌨" -> "Switch keyboard"
                "⌫" -> "Backspace"
                "↵" -> "Enter"
                else -> caption
            }
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(KeyColor)
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply { setMargins(Gap, Gap, Gap, Gap) }
            setOnClickListener { onClick() }
        }

    private companion object {
        val Rows = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
        const val Background = 0xFF17171C.toInt()
        const val KeyColor = 0xFF2A2A33.toInt()
        const val Pad = 8
        const val Gap = 3
        const val RowHeight = 132
    }
}
