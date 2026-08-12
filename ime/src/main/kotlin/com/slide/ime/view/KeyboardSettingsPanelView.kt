package com.slide.ime.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.slide.core.settings.KeyboardSettings
import com.slide.core.theme.KeyboardTheme
import com.slide.core.theme.Themes

/**
 * Keyboard-sized preferences surface shown over the keys.
 *
 * This deliberately lives in the IME window. Launching an activity from a keyboard toolbar takes
 * the user out of the editor, and on some vendor task stacks can leave that activity sitting over
 * the app they were trying to type in. Keeping the common controls here makes Back a simple panel
 * dismissal and lets editor lifecycle callbacks reset the surface synchronously.
 */
class KeyboardSettingsPanelView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onKeyboardSettingsDismissed()
        fun onKeyboardSettingsChanged(settings: KeyboardSettings)
    }

    var listener: Listener? = null

    var keyboardTheme: KeyboardTheme = Themes.Light
        set(value) {
            field = value
            applyTheme()
        }

    var settings: KeyboardSettings = KeyboardSettings()
        set(value) {
            field = value
            bindSettings()
        }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    private val primaryText = mutableListOf<TextView>()
    private val secondaryText = mutableListOf<TextView>()
    private val dividers = mutableListOf<View>()
    private val switches = mutableListOf<ToggleBinding>()
    private val themeChips = linkedMapOf<ThemeOption, TextView>()
    private var binding = false

    private val backButton = BackIconView(context).apply {
        contentDescription = "Close keyboard settings"
        isClickable = true
        isFocusable = true
        setOnClickListener { listener?.onKeyboardSettingsDismissed() }
    }

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Keyboard settings"

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), 0, dp(12f), 0)
        }
        header.addView(backButton, LayoutParams(dp(48f), dp(48f)))
        header.addView(
            primaryLabel("Keyboard settings", 18f, bold = true),
            LayoutParams(0, dp(48f), 1f),
        )
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(48f)))
        addDivider(this)

        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(20f))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addSection(body, "Theme")
        addThemePicker(body)
        addSection(body, "Typing")
        addToggle(body, "Gesture typing", read = { it.gestureTypingEnabled }) { value, checked ->
            value.copy(gestureTypingEnabled = checked)
        }
        addToggle(
            body,
            "Suggestion strip",
            "Show word candidates above the keys.",
            read = { it.suggestionsEnabled },
        ) { value, checked -> value.copy(suggestionsEnabled = checked) }
        addToggle(
            body,
            "Autocorrection",
            "Correct likely misspellings when a word is finished.",
            read = { it.autocorrectEnabled },
            enabledWhen = { it.suggestionsEnabled },
        ) { value, checked -> value.copy(autocorrectEnabled = checked) }
        addToggle(body, "Auto-capitalization", read = { it.autoCapitalize }) { value, checked ->
            value.copy(autoCapitalize = checked)
        }
        addToggle(body, "Double-space period", read = { it.doubleSpacePeriod }) { value, checked ->
            value.copy(doubleSpacePeriod = checked)
        }
        addToggle(body, "Block offensive words", read = { it.blockOffensiveWords }) { value, checked ->
            value.copy(blockOffensiveWords = checked)
        }

        addSection(body, "Appearance and feedback")
        addToggle(body, "Number row", read = { it.showNumberRow }) { value, checked ->
            value.copy(showNumberRow = checked)
        }
        addToggle(body, "Key borders", read = { it.showKeyBorders }) { value, checked ->
            value.copy(showKeyBorders = checked)
        }
        addToggle(body, "Key popup preview", read = { it.showKeyPreview }) { value, checked ->
            value.copy(showKeyPreview = checked)
        }
        addToggle(body, "Haptic feedback", read = { it.hapticEnabled }) { value, checked ->
            value.copy(hapticEnabled = checked)
        }
        addToggle(body, "Sound on keypress", read = { it.soundEnabled }) { value, checked ->
            value.copy(soundEnabled = checked)
        }

        addSection(body, "Privacy")
        addToggle(
            body,
            "Incognito mode",
            "Stop learning new words and phrases until this is turned off.",
            read = { it.incognitoModeEnabled },
        ) { value, checked -> value.copy(incognitoModeEnabled = checked) }

        bindSettings()
        applyTheme()
    }

    private fun addThemePicker(parent: LinearLayout) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8f))
        }
        themeOptions().forEach { option ->
            val chip = TextView(context).apply {
                text = option.label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(13f), 0, dp(13f), 0)
                minWidth = dp(64f)
                contentDescription = "${option.label} theme"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val updated = settings.copy(themeId = option.id)
                    settings = updated
                    listener?.onKeyboardSettingsChanged(updated)
                }
            }
            themeChips[option] = chip
            row.addView(
                chip,
                LayoutParams(LayoutParams.WRAP_CONTENT, dp(38f)).apply {
                    marginEnd = dp(8f)
                },
            )
        }
        parent.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(row)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(48f)),
        )
    }

    private fun addSection(parent: LinearLayout, title: String) {
        parent.addView(
            primaryLabel(title, 13f, bold = true).apply {
                setPadding(dp(8f), dp(12f), dp(8f), dp(6f))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    private fun addToggle(
        parent: LinearLayout,
        title: String,
        description: String? = null,
        read: (KeyboardSettings) -> Boolean,
        enabledWhen: (KeyboardSettings) -> Boolean = { true },
        update: (KeyboardSettings, Boolean) -> KeyboardSettings,
    ) {
        val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        labels.addView(primaryLabel(title, 15f), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (description != null) {
            labels.addView(
                secondaryLabel(description),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }

        val control = Switch(context).apply {
            showText = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val row = AccessibleToggleRow(context, control).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (description == null) 50f else 62f)
            setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
            isClickable = true
            isFocusable = true
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = if (description == null) title else "$title. $description"
            setOnClickListener { if (control.isEnabled) control.toggle() }
        }
        row.addView(labels, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(control, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        parent.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addDivider(parent)

        val toggle = ToggleBinding(row, control, read, enabledWhen, update)
        switches += toggle
        control.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            val updated = toggle.update(settings, checked)
            settings = updated
            listener?.onKeyboardSettingsChanged(updated)
            row.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    /**
     * Makes the full settings row one switch-shaped accessibility target. Its visual labels and
     * platform Switch remain descendants for layout and touch, but are hidden from accessibility
     * so TalkBack does not stop on the same setting two or three times.
     */
    private class AccessibleToggleRow(
        context: Context,
        private val control: Switch,
    ) : LinearLayout(context), Checkable {
        override fun isChecked(): Boolean = control.isChecked

        override fun setChecked(checked: Boolean) {
            control.isChecked = checked
        }

        override fun toggle() {
            control.toggle()
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = Switch::class.java.name
            info.isCheckable = true
            info.isChecked = isChecked
        }
    }

    private fun primaryLabel(text: String, sizeSp: Float, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            primaryText += this
        }

    private fun secondaryLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setLineSpacing(0f, 1.05f)
        secondaryText += this
    }

    private fun addDivider(parent: LinearLayout) {
        val divider = View(context)
        dividers += divider
        parent.addView(
            divider,
            LayoutParams(LayoutParams.MATCH_PARENT, maxOf(1, dp(0.5f))).apply {
                marginStart = dp(8f)
                marginEnd = dp(8f)
            },
        )
    }

    private fun bindSettings() {
        binding = true
        switches.forEach { toggle ->
            val enabled = toggle.enabledWhen(settings)
            toggle.control.isEnabled = enabled
            toggle.row.isEnabled = enabled
            toggle.row.alpha = if (enabled) 1f else 0.42f
            toggle.control.isChecked = toggle.read(settings)
        }
        binding = false
        applyThemeChips()
    }

    private fun applyTheme() {
        setBackgroundColor(keyboardTheme.background)
        primaryText.forEach { it.setTextColor(keyboardTheme.keyText) }
        secondaryText.forEach { it.setTextColor(keyboardTheme.hintText) }
        dividers.forEach { it.setBackgroundColor(keyboardTheme.divider) }
        backButton.iconColor = keyboardTheme.keyText
        backButton.background = roundedBackground(keyboardTheme.specialKeyBackground, dp(24f).toFloat())

        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        switches.forEach { toggle ->
            toggle.control.thumbTintList = ColorStateList(
                states,
                intArrayOf(keyboardTheme.accentText, keyboardTheme.hintText),
            )
            toggle.control.trackTintList = ColorStateList(
                states,
                intArrayOf(
                    keyboardTheme.accentBackground,
                    ColorUtils.setAlphaComponent(keyboardTheme.specialKeyBackground, 0xCC),
                ),
            )
        }
        applyThemeChips()
    }

    private fun applyThemeChips() {
        themeChips.forEach { (option, chip) ->
            val selected = settings.themeId == option.id
            val fill = option.theme?.keyBackground ?: keyboardTheme.accentBackground
            val text = option.theme?.keyText ?: keyboardTheme.accentText
            chip.setTextColor(text)
            chip.setTypeface(
                chip.typeface,
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
            )
            chip.contentDescription = "${option.label} theme" + if (selected) ", selected" else ""
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(19f).toFloat()
                setColor(fill)
                setStroke(
                    dp(if (selected) 2f else 1f),
                    if (selected) keyboardTheme.accentBackground else keyboardTheme.divider,
                )
            }
            chip.isSelected = selected
        }
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun themeOptions(): List<ThemeOption> =
        listOf(ThemeOption(Themes.ID_DYNAMIC, "Dynamic", null)) +
            Themes.presets.map { ThemeOption(it.id, it.name, it) }

    private data class ToggleBinding(
        val row: View,
        val control: Switch,
        val read: (KeyboardSettings) -> Boolean,
        val enabledWhen: (KeyboardSettings) -> Boolean,
        val update: (KeyboardSettings, Boolean) -> KeyboardSettings,
    )

    private data class ThemeOption(
        val id: String,
        val label: String,
        val theme: KeyboardTheme?,
    )
}

/** Geometry-drawn back arrow avoids depending on an OEM font glyph. */
private class BackIconView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * density
    }

    var iconColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = iconColor
        val centerX = width / 2f
        val centerY = height / 2f
        val horizontal = 7f * density
        val vertical = 6f * density
        canvas.drawLine(centerX + horizontal / 2f, centerY - vertical, centerX - horizontal / 2f, centerY, paint)
        canvas.drawLine(centerX - horizontal / 2f, centerY, centerX + horizontal / 2f, centerY + vertical, paint)
    }
}
