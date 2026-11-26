package com.app.assistant.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.assistant.tts.TtsEngine
import com.app.assistant.tts.TtsMode
import com.app.assistant.tts.VoiceOption
import com.app.assistant.viewmodel.SettingsViewModel

/**
 * Выбор голоса для ТЕКУЩЕГО движка синтеза.
 *
 * Владелец 2026-11-26: «дай выбор голосов для любых голосовых движков, пусть выбор
 * зависит от того, какой движок включён». Поэтому список голосов строится по режиму:
 *   OFFLINE → Piper (модели скачиваются, 4 голоса)
 *   API + Edge → Светлана/Дмитрий (у Edge только два русских голоса)
 *   API + Google → четыре Wavenet
 *   NATIVE → установленные в Android русские голоса (спрашиваем TextToSpeech)
 * Привязки агентов хранятся отдельно для каждого движка, поэтому переключение режима
 * не теряет выбор и не подставляет чужой id.
 */
@Composable
fun OfflineVoicesSection(settingsViewModel: SettingsViewModel) {
    val mode by settingsViewModel.ttsMode.collectAsState()
    val installed by settingsViewModel.installedVoices.collectAsState()
    val downloading by settingsViewModel.downloadingVoice.collectAsState()
    val defaultVoices by settingsViewModel.defaultVoices.collectAsState()
    val agentNames by settingsViewModel.agentVoiceNames.collectAsState()
    val agentVoicesByEngine by settingsViewModel.agentVoices.collectAsState()
    val nativeVoices by settingsViewModel.nativeVoices.collectAsState()

    val engine = settingsViewModel.currentEngine()
    val options: List<VoiceOption> = when (engine) {
        TtsEngine.NATIVE -> nativeVoices
        else -> settingsViewModel.voiceOptionsFor(engine)
    }
    val defaultVoice = defaultVoices[engine.name]?.takeIf { it in options.map { o -> o.id } }
        ?: options.firstOrNull()?.id ?: ""
    val agentVoices = agentVoicesByEngine[engine.name] ?: emptyMap()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(titleFor(engine), style = MaterialTheme.typography.titleSmall)
        Text(descriptionFor(engine, mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        if (options.isEmpty()) {
            Text(
                if (engine == TtsEngine.NATIVE) "В системе нет установленных русских голосов синтеза."
                else "Нет доступных голосов для этого движка.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        options.forEach { voice ->
            val isPiper = engine == TtsEngine.PIPER
            val isInstalled = !isPiper || installed.contains(voice.id)
            val isDownloading = isPiper && downloading == voice.id
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = defaultVoice == voice.id,
                    onClick = { settingsViewModel.setDefaultVoice(engine, voice.id) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(voice.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (isPiper && !isInstalled) "${voice.subtitle} · не загружен" else voice.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { settingsViewModel.previewVoice(engine, voice.id) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Прослушать ${voice.title}")
                }
                if (isPiper) {
                    when {
                        isDownloading -> {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton(onClick = { settingsViewModel.cancelTtsModelDownload() }) {
                                Text("Отмена")
                            }
                        }
                        isInstalled -> OutlinedButton(onClick = { settingsViewModel.deleteTtsModel(voice.id) }) {
                            Text("Удалить")
                        }
                        else -> Button(onClick = { settingsViewModel.startTtsModelDownload(voice.id) }) {
                            Text("Скачать")
                        }
                    }
                }
            }
        }

        if (options.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Голос по умолчанию", style = MaterialTheme.typography.titleSmall)
            Text(
                "Им говорят служебные сообщения и агенты без своей привязки. Выбор — радиокнопкой в списке выше.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Text("Голос агентов", style = MaterialTheme.typography.titleSmall)
            Text(
                "Агент определяется по префиксу «[Имя]» в начале ответа. Привязки хранятся отдельно для каждого движка.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            agentNames.forEach { name ->
                AgentVoiceRow(
                    agentName = name,
                    options = options,
                    stored = agentVoices[name] ?: defaultVoice,
                    onSelect = { settingsViewModel.setAgentVoice(engine, name, it) },
                    onPreview = { settingsViewModel.previewVoice(engine, agentVoices[name] ?: defaultVoice) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        var showTrace by remember { mutableStateOf(false) }
        var traceText by remember { mutableStateOf("") }
        val clipboard = LocalClipboardManager.current
        OutlinedButton(onClick = {
            traceText = settingsViewModel.ttsTrace()
            showTrace = true
        }) { Text("Диагностика синтеза") }
        if (showTrace) {
            AlertDialog(
                onDismissRequest = { showTrace = false },
                title = { Text("Путь текста в звук") },
                text = {
                    Text(
                        text = traceText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(traceText))
                        showTrace = false
                    }) { Text("Копировать") }
                },
                dismissButton = { TextButton(onClick = { showTrace = false }) { Text("Закрыть") } },
            )
        }
    }
}

@Composable
private fun AgentVoiceRow(
    agentName: String,
    options: List<VoiceOption>,
    stored: String,
    onSelect: (String) -> Unit,
    onPreview: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.id == stored } ?: options.firstOrNull()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(agentName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onPreview) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Прослушать голос агента $agentName")
        }
        OutlinedButton(onClick = { expanded = true }) {
            Text(current?.title ?: "—")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { voice ->
                DropdownMenuItem(
                    text = { Text("${voice.title} · ${voice.subtitle}") },
                    onClick = {
                        expanded = false
                        onSelect(voice.id)
                    },
                )
            }
        }
    }
}

private fun titleFor(engine: TtsEngine): String = when (engine) {
    TtsEngine.PIPER -> "Голоса Piper (офлайн)"
    TtsEngine.EDGE -> "Голоса Edge TTS (онлайн)"
    TtsEngine.GOOGLE -> "Голоса Google TTS (онлайн)"
    TtsEngine.NATIVE -> "Системные голоса Android"
}

private fun descriptionFor(engine: TtsEngine, mode: TtsMode): String = when (engine) {
    TtsEngine.PIPER ->
        "Модели Piper, ~64 МБ каждая. Скачайте нужные: работают без интернета, у каждого агента может быть свой голос."
    TtsEngine.EDGE ->
        "Edge TTS — облачный, бесплатный. Русских голосов у сервиса только два, поэтому уникальность между агентами ограничена."
    TtsEngine.GOOGLE ->
        "Google Cloud TTS — облачный, нужен API-ключ. Четыре русских голоса Wavenet."
    TtsEngine.NATIVE ->
        "Системный синтез Android: голоса, установленные в системе. Ничего скачивать не нужно."
}
