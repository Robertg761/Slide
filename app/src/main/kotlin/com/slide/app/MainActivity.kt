package com.slide.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.slide.asr.WhisperModel
import com.slide.core.settings.KeyboardSettings
import com.slide.core.settings.SettingsRepository
import com.slide.core.theme.Themes
import com.slide.engine.lexicon.UserDictionaryStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = SettingsRepository(applicationContext)

        setContent {
            SlideAppTheme {
                SetupScreen(repository)
            }
        }
    }
}

@Composable
private fun SlideAppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun SetupScreen(repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState(initial = KeyboardSettings())

    var enabled by remember { mutableStateOf(isKeyboardEnabled(context)) }
    var selected by remember { mutableStateOf(isKeyboardSelected(context)) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var showClearLearnedDataConfirmation by remember { mutableStateOf(false) }
    var clearingLearnedData by remember { mutableStateOf(false) }
    var privacyMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings.updateChecksEnabled, settings.includeAlphaUpdates) {
        if (settings.updateChecksEnabled) {
            runCatching { UpdateManager.check(context, settings.includeAlphaUpdates) }
                .onSuccess { availableUpdate = it }
        }
    }

    // Both checks change outside the app, so re-read them every time we come back to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = isKeyboardEnabled(context)
                selected = isKeyboardSelected(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Slide", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "A gesture-first keyboard with fully on-device voice typing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StepCard(
                step = 1,
                title = "Enable Slide",
                subtitle = if (enabled) "Enabled in system settings." else "Turn Slide on in the keyboard list.",
                done = enabled,
                actionLabel = "Open keyboard settings",
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
            )

            StepCard(
                step = 2,
                title = "Switch to Slide",
                subtitle = if (selected) "Slide is your active keyboard." else "Pick Slide from the input method picker.",
                done = selected,
                actionLabel = "Choose input method",
                enabledAction = enabled,
                onAction = {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showInputMethodPicker()
                },
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleMedium)

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            ThemeSwatch(
                                color = MaterialTheme.colorScheme.primary,
                                label = "Dynamic",
                                selected = settings.themeId == Themes.ID_DYNAMIC,
                                onClick = {
                                    scope.launch { repository.update { it.copy(themeId = Themes.ID_DYNAMIC) } }
                                },
                            )
                        }
                        items(Themes.presets.size) { index ->
                            val theme = Themes.presets[index]
                            ThemeSwatch(
                                color = Color(theme.background),
                                label = theme.name,
                                selected = settings.themeId == theme.id,
                                onClick = {
                                    scope.launch { repository.update { it.copy(themeId = theme.id) } }
                                },
                            )
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Keyboard", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

                    SettingSwitch("Gesture typing", settings.gestureTypingEnabled) { value ->
                        scope.launch { repository.update { it.copy(gestureTypingEnabled = value) } }
                    }
                    SettingSwitch(
                        label = "Suggestion strip",
                        checked = settings.suggestionsEnabled,
                        description = "Shows word candidates and is required for autocorrection.",
                    ) { value ->
                        scope.launch { repository.update { it.copy(suggestionsEnabled = value) } }
                    }
                    SettingSwitch(
                        label = "Autocorrection",
                        checked = settings.autocorrectEnabled,
                        description = if (settings.suggestionsEnabled) {
                            "Corrects likely misspellings when you finish a word."
                        } else if (settings.autocorrectEnabled) {
                            "Paused while the suggestion strip is off; it will resume when the strip is on."
                        } else {
                            "Turn on the suggestion strip to enable autocorrection."
                        },
                        enabled = settings.suggestionsEnabled,
                    ) { value ->
                        scope.launch { repository.update { it.copy(autocorrectEnabled = value) } }
                    }
                    SettingSwitch("Block offensive words", settings.blockOffensiveWords) { value ->
                        scope.launch { repository.update { it.copy(blockOffensiveWords = value) } }
                    }
                    SettingSwitch("Number row", settings.showNumberRow) { value ->
                        scope.launch { repository.update { it.copy(showNumberRow = value) } }
                    }
                    SettingSwitch("Key borders", settings.showKeyBorders) { value ->
                        scope.launch { repository.update { it.copy(showKeyBorders = value) } }
                    }
                    SettingSwitch("Key popup preview", settings.showKeyPreview) { value ->
                        scope.launch { repository.update { it.copy(showKeyPreview = value) } }
                    }
                    SettingSwitch("Haptic feedback", settings.hapticEnabled) { value ->
                        scope.launch { repository.update { it.copy(hapticEnabled = value) } }
                    }
                    SettingSwitch("Sound on keypress", settings.soundEnabled) { value ->
                        scope.launch { repository.update { it.copy(soundEnabled = value) } }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Privacy and learned data", style = MaterialTheme.typography.titleMedium)
                    SettingSwitch(
                        label = "Incognito mode",
                        checked = settings.incognitoModeEnabled,
                        description = "Stops Slide from learning new words and phrases. Existing learned data stays until you clear it.",
                    ) { value ->
                        scope.launch {
                            repository.update { it.copy(incognitoModeEnabled = value) }
                        }
                    }
                    Text(
                        "Slide keeps learned words and phrases only on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        enabled = !clearingLearnedData,
                        onClick = { showClearLearnedDataConfirmation = true },
                    ) {
                        Text(if (clearingLearnedData) "Clearing…" else "Clear learned data")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Voice typing", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Speech is recognised on this device. Nothing is recorded or sent anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val selected = WhisperModel.fromId(settings.voiceModelId)
                    WhisperModel.entries.forEach { model ->
                        ModelChoice(
                            label = model.label,
                            description = model.description,
                            selected = model == selected,
                            onClick = {
                                scope.launch {
                                    repository.update { it.copy(voiceModelId = model.name) }
                                }
                            },
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Updates", style = MaterialTheme.typography.titleMedium)
                    Text("GitHub is contacted only when you check. Android always confirms installation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingSwitch("Check GitHub for updates", settings.updateChecksEnabled) { value -> scope.launch { repository.update { it.copy(updateChecksEnabled = value) } } }
                    if (settings.updateChecksEnabled) {
                        SettingSwitch("Include alpha prereleases", settings.includeAlphaUpdates) { value -> scope.launch { repository.update { it.copy(includeAlphaUpdates = value) } } }
                        Button(onClick = { scope.launch { runCatching { UpdateManager.check(context, settings.includeAlphaUpdates) }.onSuccess { availableUpdate = it; updateMessage = if (it == null) "You already have the newest selected release." else null }.onFailure { updateMessage = "Could not check for updates: ${it.message ?: "network error"}" } } }) { Text("Check now") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Try it", style = MaterialTheme.typography.titleMedium)
                    var text by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Type here") },
                    )
                    SelectionContainer {
                        Text(
                            "Swipe across the letters to type a word, then tap an alternative in " +
                                "the suggestion bar if it guessed wrong. Tap the microphone to dictate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    availableUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { if (!downloading) availableUpdate = null },
            title = { Text("Slide ${update.version} is available") },
            text = {
                Text(
                    if (downloading) {
                        "Downloading Slide ${update.version}. Slide is large — this can take a " +
                            "minute. Android will ask you to confirm the installation."
                    } else {
                        update.notes.ifBlank { "A newer signed Slide release is available." }
                    },
                )
            },
            confirmButton = {
                Button(
                    enabled = !downloading,
                    onClick = {
                        scope.launch {
                            downloading = true
                            runCatching { UpdateManager.downloadAndInstall(context, update) }
                                .onSuccess { outcome ->
                                    availableUpdate = null
                                    if (outcome == InstallOutcome.NeedsPermission) {
                                        updateMessage = "Allow Slide to install apps, then tap " +
                                            "Check now again to finish updating."
                                    }
                                }
                                .onFailure {
                                    availableUpdate = null
                                    updateMessage = "Update download failed: " +
                                        (it.message ?: "unknown error") +
                                        "\n\nYou can also download the APK from the Slide releases " +
                                        "page on GitHub and install it by hand."
                                }
                            downloading = false
                        }
                    },
                ) { Text(if (downloading) "Downloading…" else "Download and install") }
            },
            dismissButton = {
                Button(enabled = !downloading, onClick = { availableUpdate = null }) { Text("Not now") }
            },
        )
    }
    updateMessage?.let { message -> AlertDialog(onDismissRequest = { updateMessage = null }, title = { Text("Updates") }, text = { Text(message) }, confirmButton = { Button(onClick = { updateMessage = null }) { Text("OK") } }) }
    if (showClearLearnedDataConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (!clearingLearnedData) showClearLearnedDataConfirmation = false
            },
            title = { Text("Clear learned data?") },
            text = {
                Text(
                    "Slide will forget the personal words and word pairs it learned from your " +
                        "typing. This cannot be undone.",
                )
            },
            confirmButton = {
                Button(
                    enabled = !clearingLearnedData,
                    onClick = {
                        scope.launch {
                            clearingLearnedData = true
                            try {
                                // Marker creation and epoch publication are one privacy-critical
                                // operation. Once the user confirms, leaving this screen must not
                                // cancel between the durable marker and clearing the live IME.
                                val deletionRequested = withContext(NonCancellable) {
                                    val requested = withContext(Dispatchers.IO) {
                                        UserDictionaryStore(context.applicationContext)
                                            .requestDeletion()
                                    }
                                    if (requested) repository.notifyLearnedDataCleared()
                                    requested
                                }
                                if (!deletionRequested) {
                                    throw IOException("the clear request could not be stored safely")
                                }
                                showClearLearnedDataConfirmation = false
                                privacyMessage = "Learned words and phrases were cleared."
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                showClearLearnedDataConfirmation = false
                                privacyMessage = "Could not clear learned data: " +
                                    (error.message ?: "unknown error")
                            } finally {
                                clearingLearnedData = false
                            }
                        }
                    },
                ) { Text(if (clearingLearnedData) "Clearing…" else "Clear") }
            },
            dismissButton = {
                Button(
                    enabled = !clearingLearnedData,
                    onClick = { showClearLearnedDataConfirmation = false },
                ) { Text("Cancel") }
            },
        )
    }
    privacyMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { privacyMessage = null },
            title = { Text("Learned data") },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = { privacyMessage = null }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun ModelChoice(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null) // the whole row is the target
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepCard(
    step: Int,
    title: String,
    subtitle: String,
    done: Boolean,
    actionLabel: String,
    enabledAction: Boolean = true,
    onAction: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(
                            if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (done) "✓" else step.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAction, enabled = enabledAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    description: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.38f,
                    ),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ThemeSwatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(color, CircleShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun isKeyboardEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

private fun isKeyboardSelected(context: Context): Boolean {
    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    return current?.startsWith(context.packageName) == true
}
