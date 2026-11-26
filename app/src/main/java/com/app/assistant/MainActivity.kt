package com.app.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.app.assistant.ui.screen.SetupUI
import com.app.assistant.viewmodel.MainViewModel
import com.app.assistant.viewmodel.MainViewModelFactory
import com.app.assistant.viewmodel.SettingsViewModel
import com.app.assistant.viewmodel.SettingsViewModelFactory
import com.app.assistant.viewmodel.UIEvent
import com.app.assistant.viewmodel.onLocationFailed
import com.app.assistant.viewmodel.onLocationReceived
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsResponse
import com.google.android.gms.location.Priority
import com.google.android.gms.location.SettingsClient
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(application, intent.getBooleanExtra("speak", false))
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModelFactory(
            com.app.assistant.repository.SettingsRepository(application),
            com.app.assistant.viewmodel.MainViewModelFactory.okHttpClient,
            com.app.assistant.viewmodel.MainViewModelFactory.customConfigHttpClient
        )
    }

    private lateinit var textToSpeechManager: com.app.assistant.tts.TtsManager
    private lateinit var speechRecognizerManager: com.app.assistant.speech.SpeechRecognizerManager

    private var isScreenServiceRunning = false
    private var isActivityStarted = false
    /**
     * Запуск «как ассистент» (долгое нажатие home / VOICE_COMMAND) обрабатываем один раз
     * за жизнь Activity: onStart случается и при возврате из настроек, и повторное
     * включение режима разговора там было бы сюрпризом.
     */
    private var assistantLaunchHandled = false
    /** Момент, с которого микрофон непрерывно громче порога перебивания. */
    private var loudSince = 0L

    companion object {
        /** RMS-порог «человек говорит» (доля от полной шкалы). */
        private const val BARGE_IN_RMS = 0.12f
        /** Сколько держится громкость, чтобы это считалось перебиванием (мс). */
        private const val BARGE_IN_HOLD_MS = 250L
    }
    private var activeCollectionJob: kotlinx.coroutines.Job? = null

    private val screenCaptureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, com.app.assistant.camera.ScreenCaptureService::class.java).apply {
                action = com.app.assistant.camera.ScreenCaptureService.ACTION_START
                putExtra(com.app.assistant.camera.ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(com.app.assistant.camera.ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            com.app.assistant.camera.ScreenCaptureServiceHelper.isMicMuted = viewModel.isMicMuted.value
            com.app.assistant.camera.ScreenCaptureServiceHelper.isHandsFreeActive = viewModel.isHandsFreeModeActive.value
            ContextCompat.startForegroundService(this, serviceIntent)
            isScreenServiceRunning = true
            viewModel.setScreenModeActive(true)
        } else {
            Toast.makeText(this, "Доступ к захвату экрана запрещён", Toast.LENGTH_SHORT).show()
            isScreenServiceRunning = false
            viewModel.setScreenModeActive(false)
        }
    }

    private fun startScreenCaptureFlow() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                mediaProjectionManager.createScreenCaptureIntent(config)
            } else {
                mediaProjectionManager.createScreenCaptureIntent()
            }
            screenCaptureLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to launch screen capture intent", e)
            Toast.makeText(this, "Захват экрана не поддерживается или не запустился", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopScreenCaptureService() {
        val serviceIntent = Intent(this, com.app.assistant.camera.ScreenCaptureService::class.java).apply {
            action = com.app.assistant.camera.ScreenCaptureService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allowOnLockScreen()
        enableEdgeToEdge()
        
        textToSpeechManager = com.app.assistant.tts.TtsEngineSelector(
            context = this,
            settingsRepository = com.app.assistant.repository.SettingsRepository(application)
        ) { isSpeaking ->
            viewModel.setSpeaking(isSpeaking)
            if (::speechRecognizerManager.isInitialized) {
                speechRecognizerManager.isTtsSpeaking = isSpeaking
            }
            // Режим разговора: договорили — снова слушаем. Без этого после первого ответа
            // микрофон замолкал до следующего нажатия, то есть «разговор» был одноразовым.
            if (!isSpeaking) resumeHandsFreeListening()
        }
        speechRecognizerManager = com.app.assistant.speech.SpeechRecognizerManager(this, lifecycleScope)
        speechRecognizerManager.preLoadModelAsync()
        speechRecognizerManager.shouldIgnoreUiHidden = {
            viewModel.isScreenModeActive.value
        }

        com.app.assistant.camera.ScreenCaptureServiceHelper.onServiceStopped = {
            runOnUiThread {
                isScreenServiceRunning = false
                viewModel.setScreenModeActive(false)
            }
        }
        com.app.assistant.camera.ScreenCaptureServiceHelper.onToggleMuteRequested = {
            runOnUiThread {
                viewModel.toggleMicMute()
            }
        }


        lifecycleScope.launch {
            viewModel.isScreenModeActive.collect { active ->
                if (active) {
                    if (!isScreenServiceRunning) {
                        startScreenCaptureFlow()
                    }
                } else {
                    if (isScreenServiceRunning) {
                        isScreenServiceRunning = false
                        stopScreenCaptureService()
                    }
                }
                updateCollectionState()
            }
        }

        setContent {
            SetupUI(
                viewModel = viewModel,
                settingsViewModel = settingsViewModel,
                onToggleVisionMode = {
                    toggleVisionModeWithPermissionCheck()
                },
                onToggleScreenMode = {
                    toggleScreenModeWithPermissionCheck()
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        updateCollectionState()
        // Приложение назначено ассистентом по умолчанию: его запускают кнопкой/голосом,
        // чтобы ГОВОРИТЬ, а не читать чат. Поэтому сразу включаем режим разговора —
        // иначе владельцу приходится каждый раз нажимать микрофон, что и было жалобой.
        if (!assistantLaunchHandled && intent.getBooleanExtra("speak", false)) {
            assistantLaunchHandled = true
            viewModel.setHandsFreeModeActive(true)
        }
    }

    override fun onStop() {
        super.onStop()
        isActivityStarted = false
        updateCollectionState()
    }

    private fun updateCollectionState() {
        val shouldCollect = isActivityStarted || viewModel.isScreenModeActive.value
        if (shouldCollect) {
            startActiveCollection()
        } else {
            stopActiveCollection()
        }
    }

    private fun startActiveCollection() {
        if (activeCollectionJob != null) return
        
        Log.d("MainActivity", "Starting active flow collection (Activity started: $isActivityStarted, Screen mode active: ${viewModel.isScreenModeActive.value})")
        activeCollectionJob = lifecycleScope.launch {
            // uiEvent collection
            launch {
                viewModel.uiEvent.collect { event ->
                    handleUiEvent(event)
                }
            }
            
            // isMicMuted collection
            launch {
                viewModel.isMicMuted.collect { muted ->
                    if (::speechRecognizerManager.isInitialized) {
                        speechRecognizerManager.setMicMuted(muted)
                    }
                    com.app.assistant.camera.ScreenCaptureServiceHelper.isMicMuted = muted
                    com.app.assistant.camera.ScreenCaptureServiceHelper.onStateChanged?.invoke()
                }
            }
            
            // isHandsFreeModeActive collection
            launch {
                var wasHandsFreeActive = false
                viewModel.isHandsFreeModeActive.collect { active ->
                    com.app.assistant.camera.ScreenCaptureServiceHelper.isHandsFreeActive = active
                    com.app.assistant.camera.ScreenCaptureServiceHelper.onStateChanged?.invoke()
                    
                    if (active) {
                        wasHandsFreeActive = true
                        if (::speechRecognizerManager.isInitialized && !speechRecognizerManager.isListeningActive()) {
                            startSpeechRecognition(isHandsFree = true)
                        }
                    } else {
                        if (wasHandsFreeActive) {
                            wasHandsFreeActive = false
                            speechRecognizerManager.stop()
                        }
                    }
                }
            }
        }
    }

    private fun stopActiveCollection() {
        if (activeCollectionJob == null) return
        Log.d("MainActivity", "Stopping active flow collection")
        activeCollectionJob?.cancel()
        activeCollectionJob = null
    }

    private fun handleUiEvent(event: UIEvent) {
        when (event) {
            is UIEvent.RequestPermissions -> {
                ActivityCompat.requestPermissions(this@MainActivity, event.permissions, event.requestCode)
            }

            is UIEvent.StartIntent -> {
                try {
                    val intent = event.intent
                    if (intent.action == Intent.ACTION_CALL) {
                        if (!isExternalAudioDeviceConnected()) {
                            intent.putExtra("android.telecom.extra.START_CALL_WITH_SPEAKERPHONE", true)
                        }
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error starting intent", e)
                }
            }

            is UIEvent.ShowToast -> {
                Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
            }

            is UIEvent.SpeakText -> {
                // НЕ отбрасываем текст, если микрофон в этот момент слушает.
                //
                // Старая проверка «не слушаем и не обрабатываем голос» молча выбрасывала
                // ответ агента: ack («Секунду, уточняю.») дочитывался, режим разговора
                // успевал снова включить микрофон, и все последующие фразы (сам ответ)
                // не озвучивались — слышны были только служебные реплики. Перебивание
                // по-прежнему работает: когда человек начинает говорить, onBeginningOfSpeech
                // останавливает TTS.
                com.app.assistant.util.TtsTrace.add("activity", "SpeakText len=${event.text.length} q=${event.queueMode} listening=${viewModel.isListening.value} speaking=${viewModel.isSpeaking.value}")
                textToSpeechManager.speak(event.text, event.queueMode)
            }

            is UIEvent.StopSpeaking -> {
                textToSpeechManager.stop()
            }

            is UIEvent.StartSpeechRecognition -> {
                startSpeechRecognition(isHandsFree = viewModel.isHandsFreeModeActive.value)
            }

            is UIEvent.StopSpeechRecognition -> {
                speechRecognizerManager.stop()
            }

            is UIEvent.GetLocationForWeather -> {
                getCurrentLocationForWeather(event)
            }

            is UIEvent.ResolveLocationSettings -> {
                try {
                    event.exception.startResolutionForResult(this@MainActivity, 104)
                } catch (sendEx: IntentSender.SendIntentException) {
                    Log.e("MainActivity", "Error showing location settings dialog: ${sendEx.message}")
                }
            }
        }
    }

    private fun toggleScreenModeWithPermissionCheck() {
        if (viewModel.isScreenModeActive.value) {
            viewModel.setScreenModeActive(false)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 103)
                    return
                }
            }
            startScreenCaptureFlow()
        }
    }

    /**
     * Возобновить слушание после ответа ассистента, если включён режим разговора.
     *
     * Небольшая задержка нужна, чтобы TTS успел освободить аудиовыход (иначе системный
     * распознаватель получает ошибку аудиофокуса и режим «дёргается»). Проверки перед
     * запуском защищают от двойного старта: onStart/коллектор состояния тоже могут
     * попытаться включить слушание.
     */
    private fun resumeHandsFreeListening() {
        if (!viewModel.isHandsFreeModeActive.value) return
        if (viewModel.isListening.value || viewModel.isVoiceProcessing.value) return
        // Ответ ещё стримится — это НЕ конец речи.
        //
        // Колбэк TTS сообщает isSpeaking=false после КАЖДОГО utterance, а ack
        // («Секунду, уточняю.») — отдельный utterance: сразу после него старый код
        // включал микрофон, и следующий SpeakText (сам ответ агента) отбрасывался
        // проверкой «не слушаем» в handleUiEvent. В итоге ack и ошибки звучали, а
        // ответ приходил только текстом (жалоба владельца 2026-11-26).
        if (viewModel.chatList.any { it.isStreaming || it.isLoading }) return
        lifecycleScope.launch {
            // 900 мс вместо 400: хвост собственной фразы и эхо из динамика ещё звучат
            // в комнате, и микрофон успевал распознать их как новую команду — Масяня
            // отвечала на свою же реплику (жалоба владельца 2026-11-26).
            delay(900)
            if (
                viewModel.isHandsFreeModeActive.value &&
                !viewModel.isListening.value &&
                !viewModel.isVoiceProcessing.value &&
                viewModel.chatList.none { it.isStreaming || it.isLoading } &&
                !speechRecognizerManager.isListeningActive()
            ) {
                startSpeechRecognition(isHandsFree = true)
            }
        }
    }

    /**
     * Похоже ли распознанное на последний ответ ассистента (эхо динамика).
     *
     * Сравнение нормализованное и только по вхождению: «Поняла, Капитан» из динамика
     * должно отбрасываться, а настоящая короткая команда («Док») — проходить.
     */
    private fun isEchoOfLastAnswer(recognized: String): Boolean {
        val spoken = recognized.trim().lowercase().replace(Regex("[^а-яa-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (spoken.length < 6) return false
        val answer = viewModel.chatList.lastOrNull { !it.isMe }?.text ?: return false
        val normalized = answer.lowercase().replace(Regex("[^а-яa-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return false
        return normalized.contains(spoken) || (spoken.length > 12 && spoken.contains(normalized.take(40)))
    }

    private fun startSpeechRecognition(isHandsFree: Boolean = false) {
        // Пока TTS говорит, новый цикл слушания не начинаем: иначе микрофон ловит
        // собственный голос и обрывает озвучку (симптом «Сек..» вместо «Секунду, уточняю»).
        if (isHandsFree && textToSpeechManager.isSpeaking()) {
            com.app.assistant.util.TtsTrace.add("activity", "startListening skipped: TTS is speaking")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        if (textToSpeechManager.isSpeaking()) {
            textToSpeechManager.stop()
        }

        speechRecognizerManager.startListening(
            isHandsFree = isHandsFree,
            listener = object : com.app.assistant.speech.SpeechRecognizerManager.SpeechListener {
                override fun onReadyForSpeech() {
                    viewModel.setMicReady(true)
                }

                override fun onAudioLevelChanged(level: Float) {
                    viewModel.setAudioAmplitude(level)
                    // Перебивание по громкости: тихие звуки (тап, прокрутка, шорох,
                    // эхо собственного динамика) ниже порога и TTS не трогают. Голос
                    // человека громче порога и держится дольше BARGE_IN_HOLD_MS —
                    // только тогда останавливаем чтение.
                    if (textToSpeechManager.isSpeaking()) {
                        val now = System.currentTimeMillis()
                        if (level >= BARGE_IN_RMS) {
                            if (loudSince == 0L) loudSince = now
                            if (now - loudSince >= BARGE_IN_HOLD_MS) {
                                com.app.assistant.util.TtsTrace.add(
                                    "activity",
                                    "barge-in by loudness: level=$level hold=${now - loudSince}ms",
                                )
                                textToSpeechManager.stop()
                                loudSince = 0L
                            }
                        } else {
                            loudSince = 0L
                        }
                    } else {
                        loudSince = 0L
                    }
                }

                override fun onBeginningOfSpeech() {
                    viewModel.setListening(true)
                    viewModel.setVoiceProcessing(false)
                    // Сам факт «начало речи» больше не глушит TTS: в режиме разговора
                    // микрофон слушает постоянно, и на это событие попадали шум, эхо и
                    // прокрутка ленты. Перебивание решается по ГРОМКОСТИ в
                    // onAudioLevelChanged ниже.
                }

                override fun onEndOfSpeech() {
                    viewModel.setListening(false)
                    viewModel.setVoiceProcessing(true)
                    viewModel.setAudioAmplitude(0f)
                    triggerVibration()
                }

                override fun onError(errorCode: Int) {
                    viewModel.setListening(false)
                    viewModel.setVoiceProcessing(false)
                    viewModel.setMicReady(false)
                    viewModel.setAudioAmplitude(0f)
                    if (viewModel.isHandsFreeModeActive.value) {
                        lifecycleScope.launch {
                            delay(500)
                            if (viewModel.isHandsFreeModeActive.value) {
                                startSpeechRecognition(isHandsFree = true)
                            }
                        }
                    }
                }

                override fun onResults(recognizedText: String) {
                    // Эхо собственного ответа не должно становиться новым вопросом.
                    if (viewModel.isHandsFreeModeActive.value && isEchoOfLastAnswer(recognizedText)) {
                        com.app.assistant.util.TtsTrace.add(
                            "activity",
                            "ignored echo: ${recognizedText.take(40)}",
                        )
                        viewModel.setListening(false)
                        viewModel.setVoiceProcessing(false)
                        resumeHandsFreeListening()
                        return
                    }
                    viewModel.setListening(false)
                    viewModel.setVoiceProcessing(false)
                    viewModel.setAudioAmplitude(0f)
                    if (!viewModel.isHandsFreeModeActive.value) {
                        viewModel.setMicReady(false)
                    }
                    if (recognizedText.isNotBlank()) {
                        if (viewModel.isVisionModeActive.value || viewModel.isScreenModeActive.value) {
                            val startTimestamp = speechRecognizerManager.speechStartTimestamp
                            viewModel.onSpeechRecognizedWithVision(recognizedText, startTimestamp)
                        } else {
                            viewModel.onSpeechRecognized(recognizedText)
                        }
                    }
                }

                override fun onPartialResults(recognizedText: String) {
                    viewModel.onSpeechPartialResult(recognizedText)
                }
            }
        )
    }

    private fun getCurrentLocationForWeather(event: UIEvent.GetLocationForWeather) {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

        if (!gpsEnabled) {
            promptEnableLocation()
            viewModel.onLocationFailed(event.loadingItemId, event.speak, "GPS_OFF")
        } else {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            val locationRequest =
                LocationRequest
                    .Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                    .setWaitForAccurateLocation(true)
                    .setMaxUpdates(1)
                    .build()

            val locationCallback =
                object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        val location = locationResult.lastLocation
                        if (location != null) {
                            viewModel.onLocationReceived(
                                location.latitude,
                                location.longitude,
                                event.itemId,
                                event.loadingItemId,
                                event.speak,
                                event.categoryName,
                                event.prompt,
                            )
                        } else {
                            viewModel.onLocationFailed(event.loadingItemId, event.speak, "UNAVAILABLE")
                        }
                        fusedLocationClient.removeLocationUpdates(this)
                    }

                    override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                        if (!locationAvailability.isLocationAvailable) {
                            viewModel.onLocationFailed(event.loadingItemId, event.speak, "SUGGEST_CITY")
                        }
                    }
                }

            val permissionGranted =
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
            if (permissionGranted) {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            } else {
                viewModel.onLocationFailed(event.loadingItemId, event.speak, "PERMISSION")
            }
        }
    }

    private fun promptEnableLocation() {
        val locationRequest =
            LocationRequest
                .Builder(100, 10000)
                .setMinUpdateIntervalMillis(5000)
                .build()

        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val settingsClient: SettingsClient = LocationServices.getSettingsClient(this)
        val task: Task<LocationSettingsResponse> = settingsClient.checkLocationSettings(builder.build())

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    exception.startResolutionForResult(this@MainActivity, 104)
                } catch (sendEx: IntentSender.SendIntentException) {
                    Log.d("Location", "Error showing location settings dialog: ${sendEx.message}")
                }
            }
        }
    }



    private fun triggerVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                manager?.defaultVibrator ?: (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Vibration failed", e)
        }
    }

    private fun allowOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { // Android 8.1+
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                if (keyguardManager.isKeyguardLocked) {
                    keyguardManager.requestDismissKeyguard(this, null)
                }
            }
        } catch (ex: Exception) {
            ex.message?.let { Log.d("Exception Occurred", it) }
        }
    }

    private fun toggleVisionModeWithPermissionCheck() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 102)
        } else {
            viewModel.toggleVisionMode()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            101 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // После выдачи разрешения продолжаем ТОТ режим, который был включён:
                    // при запуске ассистентом это hands-free, и раньше здесь стартовало
                    // разовое слушание, из-за чего режим выглядел выключенным.
                    startSpeechRecognition(isHandsFree = viewModel.isHandsFreeModeActive.value)
                } else {
                    Toast.makeText(this, "Для распознавания речи нужно разрешение на микрофон", Toast.LENGTH_SHORT).show()
                }
            }
            102 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    viewModel.toggleVisionMode()
                } else {
                    Toast.makeText(this, "Для режима зрения нужно разрешение на камеру", Toast.LENGTH_SHORT).show()
                }
            }
            103 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startScreenCaptureFlow()
                } else {
                    Toast.makeText(this, "Для управления из строки состояния нужно разрешение на уведомления", Toast.LENGTH_LONG).show()
                    startScreenCaptureFlow()
                }
            }
        }
    }

    private fun isExternalAudioDeviceConnected(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            val type = device.type
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET
            ) {
                return true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    return true
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                ) {
                    return true
                }
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        com.app.assistant.camera.ScreenCaptureServiceHelper.onServiceStopped = null
        com.app.assistant.camera.ScreenCaptureServiceHelper.onToggleMuteRequested = null

        if (isFinishing) {
            if (isScreenServiceRunning) {
                stopScreenCaptureService()
            }
        }
        textToSpeechManager.shutdown()
        speechRecognizerManager.destroy()
    }
}
