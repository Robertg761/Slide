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
import android.widget.SeekBar
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
    private val sliders = mutableListOf<SliderBinding>()
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
            // Mirrors the settings app's copy so the two surfaces never tell different stories
            // about why autocorrection is not running.
            describe = {
                when {
                    it.suggestionsEnabled -> "Correct likely misspellings when a word is finished."
                    it.autocorrectEnabled ->
                        "Paused while the suggestion strip is off; it will resume when the strip is on."
                    else -> "Turn on the suggestion strip to enable autocorrection."
                }
            },
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
        addSlider(
            body,
            "Keyboard height",
            valueRange = 0.7f..1.4f,
            steps = 6,
            read = { it.keyHeightScale },
            formatValue = { "${(it * 100).toInt()}%" },
        ) { value, chosen -> value.copy(keyHeightScale = chosen) }
        addSlider(
            body,
            "Space below keys",
            valueRange = 0f..32f,
            steps = 7,
            read = { it.bottomPaddingDp },
            formatValue = { "${it.toInt()} dp" },
        ) { value, chosen -> value.copy(bottomPaddingDp = chosen) }
        addToggle(body, "Key borders", read = { it.showKeyBorders }) { value, checked ->
            value.copy(showKeyBorders = checked)
        }
        addToggle(body, "Key popup preview", read = { it.showKeyPreview }) { value, checked ->
            value.copy(showKeyPreview = checked)
        }
        addToggle(body, "Haptic feedback", read = { it.hapticEnabled }) { value, checked ->
            value.copy(hapticEnabled = checked)
        }
        addSlider(
            body,
            "Haptic strength",
            valueRange = 0.1f..1f,
            steps = 8,
            read = { it.hapticStrength },
            formatValue = { "${(it * 100).toInt()}%" },
            enabledWhen = { it.hapticEnabled },
        ) { value, chosen -> value.copy(hapticStrength = chosen) }
        addToggle(body, "Sound on keypress", read = { it.soundEnabled }) { value, checked ->
            value.copy(soundEnabled = checked)
        }
        addSlider(
            body,
            "Keypress volume",
            valueRange = 0.1f..1f,
            steps = 8,
            read = { it.soundVolume },
            formatValue = { "${(it * 100).toInt()}%" },
            enabledWhen = { it.soundEnabled },
        ) { value, chosen -> value.copy(soundVolume = chosen) }

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
        /** Settings-dependent description, re-evaluated on every bind; overrides [description]. */
        describe: ((KeyboardSettings) -> String)? = null,
        read: (KeyboardSettings) -> Boolean,
        enabledWhen: (KeyboardSettings) -> Boolean = { true },
        update: (KeyboardSettings, Boolean) -> KeyboardSettings,
    ) {
        val describeWith = describe ?: description?.let { fixed -> { _: KeyboardSettings -> fixed } }
        val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        labels.addView(primaryLabel(title, 15f), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val descriptionView = describeWith?.let { describeSetting ->
            secondaryLabel(describeSetting(settings)).also { view ->
                labels.addView(
                    view,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                )
            }
        }

        val control = Switch(context).apply {
            showText = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val row = AccessibleToggleRow(context, control).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (describeWith == null) 50f else 62f)
            setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
            isClickable = true
            isFocusable = true
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { if (control.isEnabled) control.toggle() }
        }
        row.addView(labels, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(control, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        parent.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addDivider(parent)

        val toggle = ToggleBinding(row, control, title, descriptionView, describeWith, read, enabledWhen, update)
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
     * A titled SeekBar row that mirrors the settings app's slider behaviour: the value label
     * tracks the thumb live, and the changed setting is published once the gesture settles so
     * the keyboard is not re-laid-out on every pixel of movement.
     */
    private fun addSlider(
        parent: LinearLayout,
        title: String,
        valueRange: ClosedFloatingPointRange<Float>,
        /** Intermediate stops between the endpoints, matching Compose's `Slider(steps = …)`. */
        steps: Int,
        read: (KeyboardSettings) -> Float,
        formatValue: (Float) -> String,
        enabledWhen: (KeyboardSettings) -> Boolean = { true },
        update: (KeyboardSettings, Float) -> KeyboardSettings,
    ) {
        val positions = steps + 1
        fun valueAt(progress: Int): Float =
            valueRange.start +
                (valueRange.endInclusive - valueRange.start) * progress / positions

        val valueLabel = secondaryLabel(formatValue(read(settings)))
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        header.addView(primaryLabel(title, 15f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(valueLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val control = SeekBar(context).apply {
            max = positions
            // The row carries the accessibility semantics; the bar itself stays reachable so
            // TalkBack's slider actions keep working.
            contentDescription = title
        }
        control.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                valueLabel.text = formatValue(valueAt(progress))
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                if (binding) return
                val updated = update(settings, valueAt(bar.progress))
                settings = updated
                listener?.onKeyboardSettingsChanged(updated)
            }
        })

        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
        }
        row.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        row.addView(control, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        parent.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addDivider(parent)

        sliders += SliderBinding(
            row = row,
            control = control,
            valueLabel = valueLabel,
            valueRange = valueRange,
            positions = positions,
            read = read,
            formatValue = formatValue,
            enabledWhen = enabledWhen,
        )
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
            val description = toggle.describe?.invoke(settings)
            toggle.descriptionView?.text = description
            toggle.row.contentDescription =
                if (description == null) toggle.title else "${toggle.title}. $description"
        }
        sliders.forEach { slider ->
            val enabled = slider.enabledWhen(settings)
            slider.control.isEnabled = enabled
            slider.row.alpha = if (enabled) 1f else 0.42f
            val value = slider.read(settings)
            val span = slider.valueRange.endInclusive - slider.valueRange.start
            val fraction = ((value - slider.valueRange.start) / span).coerceIn(0f, 1f)
            slider.control.progress = Math.round(fraction * slider.positions)
            slider.valueLabel.text = slider.formatValue(value)
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
        sliders.forEach { slider ->
            slider.control.thumbTintList = ColorStateList.valueOf(keyboardTheme.accentBackground)
            slider.control.progressTintList = ColorStateList.valueOf(keyboardTheme.accentBackground)
            slider.control.progressBackgroundTintList =
                ColorStateList.valueOf(keyboardTheme.divider)
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
        val title: String,
        val descriptionView: TextView?,
        val describe: ((KeyboardSettings) -> String)?,
        val read: (KeyboardSettings) -> Boolean,
        val enabledWhen: (KeyboardSettings) -> Boolean,
        val update: (KeyboardSettings, Boolean) -> KeyboardSettings,
    )

    private data class SliderBinding(
        val row: View,
        val control: SeekBar,
        val valueLabel: TextView,
        val valueRange: ClosedFloatingPointRange<Float>,
        val positions: Int,
        val read: (KeyboardSettings) -> Float,
        val formatValue: (Float) -> String,
        val enabledWhen: (KeyboardSettings) -> Boolean,
    )

    private data class ThemeOption(
        val id: String,
        val label: String,
        val theme: KeyboardTheme?,
    )
}

/** Geometry-drawn back arrow avoids depending on an OEM font glyph. Shared by the key panels. */
internal class BackIconView(context: Context) : View(context) {
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
