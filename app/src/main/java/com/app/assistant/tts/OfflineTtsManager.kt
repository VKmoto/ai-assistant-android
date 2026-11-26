package com.app.assistant.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.app.assistant.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import com.k2fsa.sherpa.onnx.*
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class, DelicateCoroutinesApi::class)
class OfflineTtsManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val onSpeakingStateChanged: (isSpeaking: Boolean) -> Unit
) : TtsManager {

    private val ttsDispatcher = newSingleThreadContext("OfflineTtsThread")
    private val job = SupervisorJob()
    private val scope = CoroutineScope(ttsDispatcher + job)
    
    private data class SpeechItem(val text: String, val voiceIdOverride: String?)

    private val speechQueue = Channel<SpeechItem>(Channel.UNLIMITED)
    private val audioQueue = Channel<GeneratedAudio>(Channel.UNLIMITED)
    
    // Кэш движков по голосу: переключение между агентами не должно каждый раз
    // поднимать 64-МБ модель заново. Два последних — компромисс между отзывчивостью и
    // памятью телефона (каждая модель ~150 МБ RAM).
    private val engines = object : LinkedHashMap<String, OfflineTts>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OfflineTts>?): Boolean =
            size > 2
    }
    private var audioTrack: AudioTrack? = null
    
    private var generatorJob: Job? = null
    private var playbackJob: Job? = null
    
    @Volatile
    private var isSpeaking = false
    @Volatile
    private var isGenerating = false
    @Volatile
    private var isPlaying = false

    private class GeneratedAudio(val samples: ShortArray, val sampleRate: Int)

    init {
        startQueueProcessing()
    }

    private val modelManager = TtsModelManager(context)

    /**
     * Запасной онлайн-голос: если Piper не смог синтезировать конкретное предложение
     * (пустой результат или исключение в native), лучше прочитать его Edge TTS, чем
     * оставить ответ беззвучным.
     */
    private var fallbackApi: EdgeTtsApiManager? = null

    private fun speakViaFallback(text: String, voiceId: String) {
        val engine = fallbackApi ?: EdgeTtsApiManager(
            context,
            settingsRepository,
            com.app.assistant.viewmodel.MainViewModelFactory.okHttpClient,
        ) {}.also { fallbackApi = it }
        engine.speak(text, TtsManager.QUEUE_ADD, voiceId)
    }

    /**
     * Поднять движок для конкретного голоса.
     *
     * Piper-модели — это VITS: model.onnx + tokens.txt + espeak-ng-data (фонемизация
     * русского). Движок кэшируется по voiceId, чтобы частые переключения агентов не
     * перезагружали модель.
     */
    /** Готов ли движок этого голоса (поднимает его синхронно, если ещё нет). */
    fun ensureVoiceReady(voiceId: String): Boolean = initTtsEngine(voiceId) != null

    /** Готов ли хоть один установленный голос — запасной путь, когда нужного нет. */
    fun ensureAnyReady(): Boolean =
        modelManager.installedVoices().any { initTtsEngine(it) != null }

    @Synchronized
    private fun initTtsEngine(voiceId: String): OfflineTts? {
        engines[voiceId]?.let { return it }
        if (!modelManager.isModelDownloaded(voiceId)) {
            Log.w("OfflineTtsManager", "Voice $voiceId is not downloaded")
            return null
        }
        return try {
            val vits = OfflineTtsVitsModelConfig(
                model = modelManager.getModelPath(voiceId),
                lexicon = "",
                tokens = modelManager.getTokensPath(voiceId),
                dataDir = modelManager.getDataDir(voiceId),
                dictDir = "",
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f,
            )
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = vits,
                    numThreads = 2,
                    debug = false,
                ),
                maxNumSentences = 1,
            )
            // JNI принимает AssetManager=null при абсолютных путях.
            val tts = OfflineTts(null, config)
            engines[voiceId] = tts
            tts
        } catch (e: Exception) {
            Log.e("OfflineTtsManager", "Failed to initialize voice $voiceId", e)
            null
        }
    }

    private fun cleanTextForTts(text: String): String {
        return text
            .replace("—", " - ")
            .replace("–", " - ")
            .replace("“", "")
            .replace("”", "")
            .replace("\"", "")
            .replace("‘", "'")
            .replace("’", "'")
            .trim()
    }

    private fun startQueueProcessing() {
        generatorJob?.cancel()
        playbackJob?.cancel()
        
        while (!audioQueue.isEmpty) {
            audioQueue.tryReceive()
        }

        generatorJob = scope.launch {
            for (item in speechQueue) {
                val text = item.text
                // Голос выбирается по префиксу «[Имя]», который ставит роутер: один чат —
                // разные агенты — разные голоса. Нет модели этого голоса — пробуем голос
                // по умолчанию, затем любой установленный, чтобы речь не пропала совсем.
                val voiceId = item.voiceIdOverride ?: settingsRepository.voiceIdForText(text)
                val tts = initTtsEngine(voiceId)
                    ?: modelManager.installedVoices().firstOrNull()?.let { initTtsEngine(it) }
                if (tts == null) {
                    Log.w("OfflineTtsManager", "No TTS voice model installed. Skipping: $text")
                    continue
                }

                val cleanedText = cleanTextForTts(VoiceResolver.stripAgentPrefix(text))
                if (cleanedText.isEmpty()) continue

                isGenerating = true
                updateSpeakingState()

                try {
                    val audio = withContext(Dispatchers.Default) {
                        tts.generate(cleanedText, 0, 1.0f)
                    }
                    val floatSamples = audio.samples
                    val sampleRate = audio.sampleRate

                    if (floatSamples.isEmpty()) {
                        Log.w("OfflineTtsManager", "Empty audio for voice $voiceId, falling back to online TTS")
                        speakViaFallback(cleanedText, voiceId)
                    }
                    if (floatSamples.isNotEmpty()) {
                        val shortSamples = ShortArray(floatSamples.size)
                        for (i in floatSamples.indices) {
                            val sample = floatSamples[i]
                            val clamped = Math.max(-1.0f, Math.min(1.0f, sample))
                            shortSamples[i] = (clamped * 32767.0f).toInt().toShort()
                        }
                        audioQueue.send(GeneratedAudio(shortSamples, sampleRate))
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e("OfflineTtsManager", "Error generating speech for voice $voiceId", e)
                    speakViaFallback(cleanedText, voiceId)
                }

                if (speechQueue.isEmpty) {
                    isGenerating = false
                    updateSpeakingState()
                }
            }
        }

        playbackJob = scope.launch {
            var activeTrack: AudioTrack? = null
            var currentSampleRate = 0

            try {
                while (coroutineContext.isActive) {
                    val audio = audioQueue.receive()
                    isPlaying = true
                    updateSpeakingState()

                    if (activeTrack == null || currentSampleRate != audio.sampleRate) {
                        activeTrack?.let {
                            try { it.stop(); it.release() } catch (e: Exception) {}
                        }
                        currentSampleRate = audio.sampleRate
                        val minBufferSize = AudioTrack.getMinBufferSize(
                            currentSampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val useVoiceCall = audioManager.isBluetoothScoOn || 
                                           audioManager.mode == AudioManager.MODE_IN_CALL || 
                                           audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
                        
                        activeTrack = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(if (useVoiceCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(currentSampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build()
                            )
                            .setBufferSizeInBytes(Math.max(minBufferSize, audio.samples.size * 2))
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                        activeTrack.play()
                        audioTrack = activeTrack
                    }

                    // Write short samples to stream track
                    activeTrack.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)

                    // If there is no more audio in the queue, and we are not generating more,
                    // we wait for the current audio block to finish playing before releasing the track.
                    if (audioQueue.isEmpty && !isGenerating && speechQueue.isEmpty) {
                        val durationMs = (audio.samples.size.toDouble() / currentSampleRate * 1000).toLong()
                        delay(durationMs)

                        // Check again to ensure no new audio arrived during the delay
                        if (audioQueue.isEmpty && !isGenerating && speechQueue.isEmpty) {
                            try {
                                activeTrack.stop()
                                activeTrack.release()
                            } catch (e: Exception) {}
                            activeTrack = null
                            audioTrack = null
                            isPlaying = false
                            updateSpeakingState()
                        }
                    }
                }
            } catch (e: CancellationException) {
                // normal cancellation
            } catch (e: Exception) {
                Log.e("OfflineTtsManager", "Error playing audio stream", e)
            } finally {
                activeTrack?.let {
                    try { it.stop(); it.release() } catch (e: Exception) {}
                }
                audioTrack = null
                isPlaying = false
                updateSpeakingState()
            }
        }
    }

    private fun updateSpeakingState() {
        val shouldBeSpeaking = isGenerating || isPlaying || !speechQueue.isEmpty || !audioQueue.isEmpty
        if (shouldBeSpeaking != isSpeaking) {
            isSpeaking = shouldBeSpeaking
            scope.launch(Dispatchers.Main) {
                onSpeakingStateChanged(shouldBeSpeaking)
            }
        }
    }

    override fun speak(text: String, queueMode: Int, voiceIdOverride: String?) {
        scope.launch {
            if (queueMode == TtsManager.QUEUE_FLUSH) {
                stopPlaybackOnly()
            }
            val sentences = splitIntoSentences(text)
            for (sentence in sentences) {
                val cleanedText = cleanTextForTts(sentence)
                if (cleanedText.isNotEmpty()) {
                    speechQueue.send(SpeechItem(cleanedText, voiceIdOverride))
                }
            }
            updateSpeakingState()
        }
    }

    private fun splitIntoSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val currentSentence = StringBuilder()
        val len = text.length
        var i = 0
        while (i < len) {
            val c = text[i]
            currentSentence.append(c)

            // Split on newline or carriage return
            if (c == '\n' || c == '\r') {
                val sentenceStr = currentSentence.toString().trim()
                if (sentenceStr.isNotEmpty()) {
                    sentences.add(sentenceStr)
                }
                currentSentence.clear()
                i++
                continue
            }

            // Split on sentence-ending punctuation followed by whitespace or end of text
            if (c == '.' || c == '?' || c == '!' || c == ':' || c == ';') {
                var isBoundary = false
                if (i + 1 == len) {
                    isBoundary = true
                } else {
                    val nextChar = text[i + 1]
                    if (nextChar.isWhitespace()) {
                        isBoundary = true
                    }
                }

                if (isBoundary) {
                    // Check if it's an abbreviation or decimal number (only for period)
                    if (c == '.' && isAbbreviationOrDecimal(text, i)) {
                        // Not a boundary
                    } else {
                        val sentenceStr = currentSentence.toString().trim()
                        if (sentenceStr.isNotEmpty()) {
                            sentences.add(sentenceStr)
                        }
                        currentSentence.clear()
                    }
                }
            }
            i++
        }

        // Add any remaining text
        val remaining = currentSentence.toString().trim()
        if (remaining.isNotEmpty()) {
            sentences.add(remaining)
        }

        return sentences
    }

    private fun isAbbreviationOrDecimal(text: String, dotIndex: Int): Boolean {
        if (dotIndex > 0 && dotIndex + 1 < text.length) {
            if (text[dotIndex - 1].isDigit() && text[dotIndex + 1].isDigit()) {
                return true
            }
        }
        var start = dotIndex - 1
        while (start >= 0 && text[start].isLetter()) {
            start--
        }
        val word = text.substring(start + 1, dotIndex).lowercase()
        val abbreviations = setOf(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "eg", "ie", "vs", "etc", "st", "co",
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p",
            "q", "r", "s", "t", "u", "v", "w", "x", "y", "z"
        )
        return word in abbreviations
    }

    private fun stopPlaybackOnly() {
        while (!speechQueue.isEmpty) {
            speechQueue.tryReceive()
        }
        
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("OfflineTtsManager", "Error stopping AudioTrack", e)
        }
        audioTrack = null
        
        startQueueProcessing()

        isGenerating = false
        isPlaying = false
        updateSpeakingState()
    }

    override fun stop() {
        stopPlaybackOnly()
    }

    override fun isSpeaking(): Boolean {
        return isSpeaking
    }

    override fun shutdown() {
        job.cancel()
        stopPlaybackOnly()
        fallbackApi?.shutdown()
        fallbackApi = null
        try {
            runBlocking(ttsDispatcher) {
                engines.values.forEach { runCatching { it.release() } }
                engines.clear()
            }
        } catch (e: Exception) {
            Log.e("OfflineTtsManager", "Error releasing offline TTS", e)
        }
        ttsDispatcher.close()
    }
}
