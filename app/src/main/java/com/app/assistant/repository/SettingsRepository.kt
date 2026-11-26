package com.app.assistant.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.app.assistant.llm.LlmProvider
import com.app.assistant.tts.VoiceResolver

class SettingsRepository(
    val context: Context,
) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val securedPreferences: SharedPreferences =
        EncryptedSharedPreferences.create(
            "secure_prefs",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    fun getIsTranslationEnabled(): Boolean = sharedPreferences.getBoolean("is_translation_enabled", false)

    fun setTranslationEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("is_translation_enabled", enabled).apply()
    }

    fun getActiveLanguageCode(): String = sharedPreferences.getString("active_language_code", "en") ?: "en"

    fun setActiveLanguageCode(languageCode: String) {
        sharedPreferences.edit().putString("active_language_code", languageCode).apply()
    }

    fun saveKeys(
        youtubeApiKey: String,
        chatApiKey: String,
        edgeTtsSubscriptionKey: String = "",
        provider: LlmProvider? = null
    ) {
        val currentProvider = provider ?: try {
            LlmProvider.valueOf(getLlmProvider())
        } catch (e: Exception) {
            LlmProvider.GROQ
        }
        with(securedPreferences.edit()) {
            if (youtubeApiKey.isNotBlank()) {
                putString("youtube_api_key", youtubeApiKey)
            }
            putString("chat_api_key_${currentProvider.name}", chatApiKey)
            if (currentProvider == LlmProvider.GROQ) {
                putString("chat_api_key", chatApiKey)
            }
            if (edgeTtsSubscriptionKey.isNotBlank()) {
                putString("edge_tts_subscription_key", edgeTtsSubscriptionKey)
            }
            apply()
        }
    }

    fun saveChatApiKey(provider: LlmProvider, key: String) {
        with(securedPreferences.edit()) {
            putString("chat_api_key_${provider.name}", key)
            if (provider == LlmProvider.GROQ) {
                putString("chat_api_key", key)
            }
            apply()
        }
    }

    fun getChatApiKey(provider: LlmProvider): String? {
        val key = securedPreferences.getString("chat_api_key_${provider.name}", null)
        if (!key.isNullOrBlank()) return key
        val legacyKey = securedPreferences.getString("chat_api_key", null)
        if (!legacyKey.isNullOrBlank()) {
            val savedProvider = try {
                LlmProvider.valueOf(getLlmProvider())
            } catch (e: Exception) {
                LlmProvider.GROQ
            }
            if (provider == savedProvider || provider == LlmProvider.GROQ) {
                return legacyKey
            }
        }
        return null
    }

    fun getYoutubeApiKey(): String? = securedPreferences.getString("youtube_api_key", null)

    fun getChatApiKey(): String? {
        val provider = try {
            LlmProvider.valueOf(getLlmProvider())
        } catch (e: Exception) {
            LlmProvider.GROQ
        }
        return getChatApiKey(provider)
    }

    fun getEdgeTtsSubscriptionKey(): String? = securedPreferences.getString("edge_tts_subscription_key", null)

    fun getLlmProvider(): String = sharedPreferences.getString("llm_provider", "GROQ") ?: "GROQ"

    fun setLlmProvider(provider: String) {
        sharedPreferences.edit().putString("llm_provider", provider).apply()
    }

    fun getLlmModel(): String = sharedPreferences.getString("llm_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"

    fun setLlmModel(model: String) {
        sharedPreferences.edit().putString("llm_model", model).apply()
    }

    fun getLlmCustomUrl(): String = sharedPreferences.getString("llm_custom_url", "https://api.example.com/v1/chat/completions") ?: "https://api.example.com/v1/chat/completions"

    fun setLlmCustomUrl(url: String) {
        sharedPreferences.edit().putString("llm_custom_url", url).apply()
    }

    fun getLlmCustomHeaders(): String = sharedPreferences.getString("llm_custom_headers", "{\"Authorization\": \"Bearer {{API_KEY}}\", \"Content-Type\": \"application/json\"}") ?: "{\"Authorization\": \"Bearer {{API_KEY}}\", \"Content-Type\": \"application/json\"}"

    fun setLlmCustomHeaders(headers: String) {
        sharedPreferences.edit().putString("llm_custom_headers", headers).apply()
    }

    fun getLlmCustomResponsePath(): String = sharedPreferences.getString("llm_custom_response_path", "choices[0].message.content") ?: "choices[0].message.content"

    fun setLlmCustomResponsePath(path: String) {
        sharedPreferences.edit().putString("llm_custom_response_path", path).apply()
    }

    fun getLlmCustomRequestTemplate(): String = sharedPreferences.getString("llm_custom_request_template", "{\n  \"model\": \"{{MODEL}}\",\n  \"messages\": {{MESSAGES}}\n}") ?: "{\n  \"model\": \"{{MODEL}}\",\n  \"messages\": {{MESSAGES}}\n}"

    fun setLlmCustomRequestTemplate(template: String) {
        sharedPreferences.edit().putString("llm_custom_request_template", template).apply()
    }

    fun getLlmCustomMessageFormat(): String = sharedPreferences.getString("llm_custom_message_format", "{\"role\": \"{{ROLE}}\", \"content\": \"{{CONTENT}}\"}") ?: "{\"role\": \"{{ROLE}}\", \"content\": \"{{CONTENT}}\"}"

    fun setLlmCustomMessageFormat(format: String) {
        sharedPreferences.edit().putString("llm_custom_message_format", format).apply()
    }

    fun getLlmCustomSystemRole(): String = sharedPreferences.getString("llm_custom_system_role", "system") ?: "system"

    fun setLlmCustomSystemRole(role: String) {
        sharedPreferences.edit().putString("llm_custom_system_role", role).apply()
    }

    fun getLlmCustomUserRole(): String = sharedPreferences.getString("llm_custom_user_role", "user") ?: "user"

    fun setLlmCustomUserRole(role: String) {
        sharedPreferences.edit().putString("llm_custom_user_role", role).apply()
    }

    fun getLlmCustomAssistantRole(): String = sharedPreferences.getString("llm_custom_assistant_role", "assistant") ?: "assistant"

    fun setLlmCustomAssistantRole(role: String) {
        sharedPreferences.edit().putString("llm_custom_assistant_role", role).apply()
    }

    fun getIsImageSupported(): Boolean = sharedPreferences.getBoolean("is_image_supported", false)
    fun setIsImageSupported(supported: Boolean) {
        sharedPreferences.edit().putBoolean("is_image_supported", supported).apply()
    }

    fun getIsAudioSupported(): Boolean = sharedPreferences.getBoolean("is_audio_supported", false)
    fun setIsAudioSupported(supported: Boolean) {
        sharedPreferences.edit().putBoolean("is_audio_supported", supported).apply()
    }

    fun getIsVideoSupported(): Boolean = sharedPreferences.getBoolean("is_video_supported", false)
    fun setIsVideoSupported(supported: Boolean) {
        sharedPreferences.edit().putBoolean("is_video_supported", supported).apply()
    }

    fun getIsDocumentSupported(): Boolean = sharedPreferences.getBoolean("is_document_supported", false)
    fun setIsDocumentSupported(supported: Boolean) {
        sharedPreferences.edit().putBoolean("is_document_supported", supported).apply()
    }

    fun getIsModelVerified(): Boolean = sharedPreferences.getBoolean("is_model_verified", false)
    fun setIsModelVerified(verified: Boolean) {
        sharedPreferences.edit().putBoolean("is_model_verified", verified).apply()
    }

    fun getSttMode(): com.app.assistant.speech.SttMode {
        val modeStr = sharedPreferences.getString("stt_mode", null)
        if (modeStr != null) {
            return try {
                com.app.assistant.speech.SttMode.valueOf(modeStr)
            } catch (e: Exception) {
                com.app.assistant.speech.SttMode.NATIVE
            }
        }
        return if (getUseLocalWhisper()) {
            com.app.assistant.speech.SttMode.PARAKEET
        } else {
            com.app.assistant.speech.SttMode.NATIVE
        }
    }

    fun setSttMode(mode: com.app.assistant.speech.SttMode) {
        sharedPreferences.edit().putString("stt_mode", mode.name).apply()
        setUseLocalWhisper(mode == com.app.assistant.speech.SttMode.PARAKEET)
    }

    fun getUseLocalWhisper(): Boolean = sharedPreferences.getBoolean("use_local_whisper", false)
    fun setUseLocalWhisper(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("use_local_whisper", enabled).apply()
    }

    fun getTtsMode(): com.app.assistant.tts.TtsMode {
        val modeStr = sharedPreferences.getString("tts_mode", com.app.assistant.tts.TtsMode.NATIVE.name)
        return try {
            com.app.assistant.tts.TtsMode.valueOf(modeStr ?: com.app.assistant.tts.TtsMode.NATIVE.name)
        } catch (e: Exception) {
            com.app.assistant.tts.TtsMode.NATIVE
        }
    }

    fun setTtsMode(mode: com.app.assistant.tts.TtsMode) {
        sharedPreferences.edit().putString("tts_mode", mode.name).apply()
    }

    // ---- голоса агентов -----------------------------------------------------
    //
    // Ответ агента приходит из роутера с префиксом «[Имя]», поэтому голос выбирается по
    // имени, а не по настройке «на весь чат»: в одном разговоре могут ответить разные
    // агенты. Маппинг хранится в обычных prefs (не секрет), имена агентов накапливаются
    // сами — так настройки показывают тех, кто реально ответил, без ручного списка.

    /**
     * Голоса привязаны к КОНКРЕТНОМУ движку: у Piper свои id моделей, у Edge и Google —
     * id голосов сервиса, у системного синтеза — имя Voice. Один общий список смешал бы
     * их, поэтому ключ хранения включает движок, а выбор в настройках зависит от режима.
     */
    fun getDefaultVoice(engine: com.app.assistant.tts.TtsEngine): String {
        val key = "tts_default_${engine.name.lowercase()}"
        return sharedPreferences.getString(key, defaultVoiceFor(engine)) ?: defaultVoiceFor(engine)
    }

    fun setDefaultVoice(engine: com.app.assistant.tts.TtsEngine, voiceId: String) {
        sharedPreferences.edit().putString("tts_default_${engine.name.lowercase()}", voiceId).apply()
    }

    private fun defaultVoiceFor(engine: com.app.assistant.tts.TtsEngine): String = when (engine) {
        com.app.assistant.tts.TtsEngine.PIPER -> com.app.assistant.tts.VoiceCatalog.DEFAULT_ID
        com.app.assistant.tts.TtsEngine.EDGE -> "ru-RU-SvetlanaNeural"
        com.app.assistant.tts.TtsEngine.GOOGLE -> "ru-RU-Wavenet-A"
        com.app.assistant.tts.TtsEngine.NATIVE -> ""
    }

    fun getAgentVoice(engine: com.app.assistant.tts.TtsEngine, agentName: String): String? =
        sharedPreferences.getString(
            "tts_agent_${engine.name.lowercase()}_${agentName.lowercase()}",
            null,
        )

    fun setAgentVoice(engine: com.app.assistant.tts.TtsEngine, agentName: String, voiceId: String) {
        sharedPreferences.edit()
            .putString("tts_agent_${engine.name.lowercase()}_${agentName.lowercase()}", voiceId)
            .apply()
        rememberAgentName(agentName)
    }

    fun getKnownAgentNames(): List<String> {
        val raw = sharedPreferences.getStringSet("tts_agent_names", emptySet()) ?: emptySet()
        val defaults = listOf("Старпом", "Масяня", "Док")
        return (raw + defaults).distinct().sorted()
    }

    fun rememberAgentName(agentName: String) {
        val name = agentName.trim()
        if (name.isEmpty()) return
        val current = sharedPreferences.getStringSet("tts_agent_names", emptySet()) ?: emptySet()
        if (current.contains(name)) return
        sharedPreferences.edit().putStringSet("tts_agent_names", current + name).apply()
    }

    fun getAgentVoices(engine: com.app.assistant.tts.TtsEngine): Map<String, String> =
        getKnownAgentNames().mapNotNull { name -> getAgentVoice(engine, name)?.let { name to it } }.toMap()

    /** Голос, которым говорить этот текст, для конкретного движка. */
    fun voiceIdForText(text: String, engine: com.app.assistant.tts.TtsEngine): String {
        val name = VoiceResolver.agentName(text) ?: return getDefaultVoice(engine)
        rememberAgentName(name)
        getAgentVoice(engine, name)?.let { return it }
        return defaultAgentVoice(engine, name) ?: getDefaultVoice(engine)
    }

    /**
     * Дефолты владельца (2026-11-26): Старпом и Масяня — женский голос, Док — мужской.
     * Для системного синтеза пол неизвестен, поэтому там просто голос по умолчанию.
     */
    private fun defaultAgentVoice(
        engine: com.app.assistant.tts.TtsEngine,
        name: String,
    ): String? = when (engine) {
        com.app.assistant.tts.TtsEngine.PIPER -> when (name.lowercase()) {
            "старпом" -> "irina"
            "масяня" -> "irina"
            "док" -> "denis"
            else -> null
        }
        com.app.assistant.tts.TtsEngine.EDGE -> if (name.lowercase() == "док") {
            "ru-RU-DmitryNeural"
        } else {
            "ru-RU-SvetlanaNeural"
        }
        com.app.assistant.tts.TtsEngine.GOOGLE -> if (name.lowercase() == "док") {
            "ru-RU-Wavenet-B"
        } else {
            "ru-RU-Wavenet-A"
        }
        com.app.assistant.tts.TtsEngine.NATIVE -> null
    }

    /** Явно выбранный агент для новых сообщений; "" = авто (по имени в тексте). */
    fun getSelectedAgent(): String = sharedPreferences.getString("selected_agent", "") ?: ""

    fun setSelectedAgent(agentName: String) {
        sharedPreferences.edit().putString("selected_agent", agentName).apply()
    }

    // ---- таск-менеджер (bbx-tasks) -----------------------------------------
    // URL и device-токен вводятся в самом экране «Задачи»: это отдельная интеграция,
    // и держать её в общих настройках рядом с LLM-ключом было бы путаницей.
    fun getTasksUrl(): String = sharedPreferences.getString("tasks_url", "") ?: ""

    fun setTasksUrl(url: String) {
        sharedPreferences.edit().putString("tasks_url", url.trim()).apply()
    }

    fun getTasksToken(): String = try {
        securedPreferences.getString("tasks_token", "") ?: ""
    } catch (e: Exception) {
        ""
    }

    fun setTasksToken(token: String) {
        securedPreferences.edit().putString("tasks_token", token.trim()).apply()
    }
}

