package com.slide.ime

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionAndAccessibilityConfigurationTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `microphone permission proxy owns an isolated disposable task`() {
        val manifestFile = File(root, "ime/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifestFile)
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val activity = (0 until document.getElementsByTagName("activity").length)
            .map { document.getElementsByTagName("activity").item(it) }
            .first {
                it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue ==
                    "com.slide.ime.MicPermissionActivity"
            }

        assertEquals("", activity.attributes.getNamedItemNS(androidNamespace, "taskAffinity").nodeValue)
        assertEquals("true", activity.attributes.getNamedItemNS(androidNamespace, "excludeFromRecents").nodeValue)
        assertEquals("true", activity.attributes.getNamedItemNS(androidNamespace, "noHistory").nodeValue)

        val source = File(root, "ime/src/main/kotlin/com/slide/ime/MicPermissionActivity.kt").readText()
        assertTrue(source.contains("Intent.FLAG_ACTIVITY_NEW_TASK"))
        assertTrue(source.contains("Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS"))
        assertTrue(source.contains("Intent.FLAG_ACTIVITY_NO_HISTORY"))
        assertTrue(source.contains("finishAndRemoveTask()"))
    }

    @Test
    fun `settings toggles expose exactly one switch accessibility target`() {
        val compose = File(root, "app/src/main/kotlin/com/slide/app/MainActivity.kt").readText()
        assertTrue(compose.contains(".toggleable("))
        assertTrue(compose.contains("role = Role.Switch"))
        assertTrue(compose.contains("Switch(checked = checked, onCheckedChange = null"))

        val panel = File(
            root,
            "ime/src/main/kotlin/com/slide/ime/view/KeyboardSettingsPanelView.kt",
        ).readText()
        assertTrue(panel.contains("AccessibleToggleRow(context, control)"))
        assertTrue(panel.contains("descendantFocusability = FOCUS_BLOCK_DESCENDANTS"))
        assertTrue(panel.contains("IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"))
        assertTrue(panel.contains("importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertTrue(panel.contains("info.className = Switch::class.java.name"))
        assertTrue(panel.contains("info.isCheckable = true"))
        assertTrue(panel.contains("info.isChecked = isChecked"))
    }
}
