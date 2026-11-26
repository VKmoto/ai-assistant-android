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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.assistant.tts.VoiceCatalog
import com.app.assistant.viewmodel.SettingsViewModel

/**
 * Настройки русских голосов синтеза и привязки голосов к агентам.
 *
 * Владелец 2026-11-26: «каждый агент должен говорить своим голосом», Старпом и Масяня —
 * женским (Ирина), Док — мужским. Поэтому здесь два независимых блока:
 *   1) какие офлайн-голоса скачаны (64 МБ каждый, скачиваются по одному);
 *   2) кто каким голосом говорит. Привязка хранится по имени агента и применяется по
 *      префиксу «[Имя]» в ответе — роутер ставит его сам, приложение ничего не угадывает.
 */
@Composable
fun OfflineVoicesSection(settingsViewModel: SettingsViewModel) {
    val installed by settingsViewModel.installedVoices.collectAsState()
    val downloading by settingsViewModel.downloadingVoice.collectAsState()
    val defaultVoice by settingsViewModel.defaultVoiceId.collectAsState()
    val agentNames by settingsViewModel.agentVoiceNames.collectAsState()
    val agentVoices by settingsViewModel.agentVoices.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Русские голоса (офлайн)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Модели Piper, ~64 МБ каждая. Скачайте нужные: переключение между ними " +
                "не требует интернета, а у каждого агента может быть свой голос.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        VoiceCatalog.voices.forEach { voice ->
            val isInstalled = installed.contains(voice.id)
            val isDownloading = downloading == voice.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${voice.title} · ${voice.gender}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (isInstalled) "Установлен" else "~${voice.sizeMb} МБ, не загружен",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isDownloading) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { settingsViewModel.cancelTtsModelDownload() }) {
                        Text("Отмена")
                    }
                } else if (isInstalled) {
                    OutlinedButton(onClick = { settingsViewModel.deleteTtsModel(voice.id) }) {
                        Text("Удалить")
                    }
                } else {
                    Button(onClick = { settingsViewModel.startTtsModelDownload(voice.id) }) {
                        Text("Скачать")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Голос по умолчанию", style = MaterialTheme.typography.titleSmall)
        Text(
            "Им говорят служебные сообщения и агенты без своей привязки.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceCatalog.voices.forEach { voice ->
                FilterChip(
                    selected = defaultVoice == voice.id,
                    onClick = { settingsViewModel.setDefaultVoice(voice.id) },
                    label = { Text(voice.title) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Голос агентов", style = MaterialTheme.typography.titleSmall)
        Text(
            "Агент определяется по префиксу «[Имя]» в начале ответа. " +
                "Список пополняется сам, когда агент впервые отвечает.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        agentNames.forEach { name ->
            AgentVoiceRow(
                agentName = name,
                stored = agentVoices[name],
                settingsViewModel = settingsViewModel,
                defaultVoice = defaultVoice,
            )
        }
    }
}

@Composable
private fun AgentVoiceRow(
    agentName: String,
    stored: String?,
    settingsViewModel: SettingsViewModel,
    defaultVoice: String,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = VoiceCatalog.byId(stored ?: defaultVoice)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(agentName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { expanded = true }) {
            Text("${current.title}${if (stored == null) " (по умолчанию)" else ""}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VoiceCatalog.voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text("${voice.title} · ${voice.gender}") },
                    onClick = {
                        expanded = false
                        settingsViewModel.setAgentVoice(agentName, voice.id)
                    },
                )
            }
        }
    }
}
