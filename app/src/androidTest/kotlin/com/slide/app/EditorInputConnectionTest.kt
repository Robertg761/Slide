package com.slide.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import androidx.test.platform.app.InstrumentationRegistry
import com.slide.core.settings.KeyboardSettings
import com.slide.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises editor callbacks and accessible keys instead of calling text helpers directly. */
class EditorInputConnectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val repository = SettingsRepository(context)
    private val imeId = ComponentName(context.packageName, "com.slide.ime.SlideInputMethodService")
        .flattenToShortString()
    private var activity: EditorTestActivity? = null
    private var originalSettings: KeyboardSettings? = null
    private var originalIme: String? = null
    private var originalHardwareKeyboard: String? = null
    private var originallyEnabled = false
    private var originalAccessibilityFlags: Int? = null

    @Before
    fun enableKeyboard() {
        originalIme = secureSetting(Settings.Secure.DEFAULT_INPUT_METHOD)
        originalHardwareKeyboard = secureSetting("show_ime_with_hard_keyboard")
        originallyEnabled = context.getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.any { it.id == imeId }
        runBlocking {
            originalSettings = repository.settings.first()
            repository.update {
                it.copy(
                    autoCapitalize = false,
                    autocorrectEnabled = false,
                    suggestionsEnabled = true,
                    doubleSpacePeriod = true,
                    incognitoModeEnabled = true,
                    hapticEnabled = false,
                    soundEnabled = false,
                )
            }
        }
        automation.serviceInfo = automation.serviceInfo.apply {
            originalAccessibilityFlags = flags
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        shell("ime enable $imeId")
        shell("ime set $imeId")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        assertEquals(imeId, secureSetting(Settings.Secure.DEFAULT_INPUT_METHOD))
    }

    @After
    fun restoreKeyboard() {
        closeEditor()
        runBlocking { originalSettings?.let { saved -> repository.update { saved } } }
        originalIme?.takeIf(String::isNotBlank)?.let { shell("ime set $it") }
        if (!originallyEnabled) shell("ime disable $imeId")
        originalHardwareKeyboard?.let {
            shell("settings put secure show_ime_with_hard_keyboard $it")
        } ?: shell("settings delete secure show_ime_with_hard_keyboard")
        originalAccessibilityFlags?.let { saved ->
            automation.serviceInfo = automation.serviceInfo.apply { flags = saved }
        }
    }

    @Test
    fun doubleSpacePunctuatesAfterComposingTextSettles() {
        typeComposingWord()
        assertText("hi")
        doubleSpace()
        assertText("hi. ")
    }

    @Test
    fun deletingBetweenSpacesBreaksThePunctuationSequence() {
        openEditor()
        press("h")
        press("i")
        press("Space")
        press("Backspace")
        press("Space")
        assertText("hi ")
    }

    @Test
    fun passwordAndNoSuggestionsEditorsKeepBothSpaces() {
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        )) {
            openEditor(InputType.TYPE_CLASS_TEXT or variation)
            press("h")
            press("i")
            doubleSpace()
            assertText("hi  ")
            closeEditor()
        }
    }

    @Test
    fun backspaceDeletesWholeEmojiAndCombiningClusters() {
        for (cluster in listOf("👨‍👩‍👧‍👦", "👍🏽", "🇨🇦", "e\u0301")) {
            openEditor(text = "a$cluster")
            press("Backspace")
            assertText("a")
            closeEditor()
        }
    }

    @Test
    fun backspaceWorksWhenEditorWithholdsSurroundingText() {
        openEditor(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            text = "ab",
            connectionMode = "no-surrounding-text",
        )
        press("Backspace")
        assertText("a")
    }

    @Test
    fun backspaceDeletesSelectionWhenSelectedTextIsUnavailable() {
        openEditor(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            text = "ab",
            connectionMode = "hide-selected-text",
            selectionStart = 1,
        )
        press("Backspace")
        assertText("a")
    }

    private fun typeComposingWord() {
        // The lexicon loads asynchronously. Retry a fresh word until this test can exercise
        // composition settlement, rather than accidentally passing through literal input.
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (true) {
            openEditor()
            press("h")
            press("i")
            var hasComposition = false
            instrumentation.runOnMainSync {
                val text = requireNotNull(activity).editor.text
                hasComposition = BaseInputConnection.getComposingSpanStart(text) == 0 &&
                    BaseInputConnection.getComposingSpanEnd(text) == 2
            }
            if (hasComposition) return
            closeEditor()
            check(SystemClock.uptimeMillis() < deadline) { "Keyboard never began composing the test word" }
            SystemClock.sleep(250)
        }
    }

    private fun doubleSpace() {
        val space = key("Space")
        val started = SystemClock.uptimeMillis()
        assertTrue(space.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue(space.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue("Emulator missed the double-space timing window", SystemClock.uptimeMillis() - started < 800)
        instrumentation.waitForIdleSync()
    }

    private fun openEditor(
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        text: String = "",
        connectionMode: String? = null,
        selectionStart: Int = text.length,
    ) {
        activity = instrumentation.startActivitySync(
            Intent(context, EditorTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("inputType", inputType)
                .putExtra("text", text)
                .putExtra("connectionMode", connectionMode)
                .putExtra("selectionStart", selectionStart),
        ) as EditorTestActivity
        waitUntil("Editor did not gain window focus") {
            var focused = false
            instrumentation.runOnMainSync { focused = requireNotNull(activity).hasWindowFocus() }
            focused
        }
        key("Space")
    }

    private fun closeEditor() {
        instrumentation.runOnMainSync { activity?.finish() }
        instrumentation.waitForIdleSync()
        activity = null
    }

    private fun press(label: String) = click(key(label))

    private fun click(node: AccessibilityNodeInfo) {
        assertTrue("Keyboard action was rejected", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun key(label: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitUntil("Keyboard key $label did not appear") {
            result = automation.windows.asSequence()
                .mapNotNull { it.root }
                .mapNotNull { findKey(it, label) }
                .firstOrNull()
            result != null
        }
        return requireNotNull(result)
    }

    private fun findKey(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.packageName == context.packageName && node.contentDescription?.toString() == label &&
            node.isClickable && node.isVisibleToUser
        ) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findKey(child, label)?.let { return it }
        }
        return null
    }

    private fun assertText(expected: String) {
        var actual = ""
        waitUntil("Editor did not receive ${quote(expected)}") {
            instrumentation.runOnMainSync { actual = requireNotNull(activity).editor.text.toString() }
            actual == expected
        }
        assertEquals(expected, actual)
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(50)
        }
    }

    private fun secureSetting(name: String): String? =
        Settings.Secure.getString(context.contentResolver, name)

    private fun shell(command: String): String {
        // UiAutomation tokenizes the command directly; shell quotes would become literal parts
        // of the IME ID. All arguments here are framework IDs or integer settings values.
        val output = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        check(!output.contains("Unknown input method") && !output.startsWith("Error")) {
            "$command failed: $output"
        }
        return output
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
