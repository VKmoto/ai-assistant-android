package com.app.assistant.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.assistant.R
import com.app.assistant.llm.CustomServerPreset
import com.app.assistant.llm.LlmProvider
import com.app.assistant.viewmodel.SettingsViewModel
import com.app.assistant.viewmodel.VerificationState
import androidx.compose.animation.AnimatedVisibility

import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import com.app.assistant.speech.SttMode
import com.app.assistant.config.SpeechConfig
import com.app.assistant.viewmodel.DownloadState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.tooling.preview.Preview
import com.app.assistant.ui.theme.AssistantTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.app.role.RoleManager
import android.content.Intent
import android.content.Context
import android.provider.Settings
import android.os.Build
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onLanguageSelected: (String) -> Unit,
) {
    LaunchedEffect(Unit) {
        settingsViewModel.refreshModelStatus()
    }

    val initialYoutubeKey = remember { settingsViewModel.loadYoutubeKey() }
    val initialEdgeTtsKey = remember { settingsViewModel.loadEdgeTtsSubscriptionKey() }
    val initialProvider = remember { settingsViewModel.loadLlmProvider() }
    val initialChatKey = remember { settingsViewModel.loadChatKey(initialProvider) }
    val initialModel = remember { settingsViewModel.loadLlmModel() }
    val initialCustomUrl = remember { settingsViewModel.loadLlmCustomUrl() }
    val initialCustomHeaders = remember { settingsViewModel.loadLlmCustomHeaders() }
    val initialCustomResponsePath = remember { settingsViewModel.loadLlmCustomResponsePath() }
    val initialCustomRequestTemplate = remember { settingsViewModel.loadLlmCustomRequestTemplate() }
    val initialCustomMessageFormat = remember { settingsViewModel.loadLlmCustomMessageFormat() }
    val initialCustomSystemRole = remember { settingsViewModel.loadLlmCustomSystemRole() }
    val initialCustomUserRole = remember { settingsViewModel.loadLlmCustomUserRole() }
    val initialCustomAssistantRole = remember { settingsViewModel.loadLlmCustomAssistantRole() }
    val initialSttMode = remember { settingsViewModel.loadSttMode() }
    val initialTtsMode = remember { settingsViewModel.loadTtsMode() }

    val isTtsInstalled by settingsViewModel.isTtsModelInstalled.collectAsState()
    val verificationState by settingsViewModel.verificationState.collectAsState()
    val fetchedModelsMap by settingsViewModel.fetchedModels.collectAsState()
    val isFetchingModels by settingsViewModel.isFetchingModels.collectAsState()
    val modelFetchError by settingsViewModel.modelFetchError.collectAsState()
    val isModelInstalled by settingsViewModel.isModelInstalled.collectAsState()
    val modelDownloadState by settingsViewModel.modelDownloadState.collectAsState()
    val ttsDownloadState by settingsViewModel.ttsModelDownloadState.collectAsState()

    val isTranslationEnabled by settingsViewModel.isTranslationEnabled.collectAsState()
    val activeLanguageCode by settingsViewModel.activeLanguageCode.collectAsState()
    val downloadedTranslationLanguages by settingsViewModel.downloadedTranslationLanguages.collectAsState()
    val downloadingTranslationLanguages by settingsViewModel.downloadingTranslationLanguages.collectAsState()
    val translationLanguages = settingsViewModel.translationLanguages
    val isDefaultAssistant by settingsViewModel.isDefaultAssistant.collectAsState()

    SettingsScreenContent(
        settingsViewModel = settingsViewModel,
        initialYoutubeKey = initialYoutubeKey,
        initialEdgeTtsKey = initialEdgeTtsKey,
        initialChatKey = initialChatKey,
        initialProvider = initialProvider,
        initialModel = initialModel,
        initialCustomUrl = initialCustomUrl,
        initialCustomHeaders = initialCustomHeaders,
        initialCustomResponsePath = initialCustomResponsePath,
        initialCustomRequestTemplate = initialCustomRequestTemplate,
        initialCustomMessageFormat = initialCustomMessageFormat,
        initialCustomSystemRole = initialCustomSystemRole,
        initialCustomUserRole = initialCustomUserRole,
        initialCustomAssistantRole = initialCustomAssistantRole,
        initialSttMode = initialSttMode,
        initialTtsMode = initialTtsMode,
        isTtsInstalled = isTtsInstalled,
        verificationState = verificationState,
        fetchedModelsMap = fetchedModelsMap,
        isFetchingModels = isFetchingModels,
        modelFetchError = modelFetchError,
        isModelInstalled = isModelInstalled,
        modelDownloadState = modelDownloadState,
        ttsDownloadState = ttsDownloadState,
        isTranslationEnabled = isTranslationEnabled,
        activeLanguageCode = activeLanguageCode,
        downloadedTranslationLanguages = downloadedTranslationLanguages,
        downloadingTranslationLanguages = downloadingTranslationLanguages,
        translationLanguages = translationLanguages,
        isDefaultAssistant = isDefaultAssistant,
        onBack = onBack,
        onResetVerificationState = { settingsViewModel.resetVerificationState() },
        onFetchModelsForProvider = { provider, key, url, headers ->
            settingsViewModel.fetchModelsForProvider(provider, key, url, headers)
        },
        onVerifyModelAndSaveCapabilities = { provider, modelName, key, url, headers, respPath, reqTempl, msgFormat, sysRole, userRole, assRole ->
            settingsViewModel.verifyModelAndSaveCapabilities(
                provider, modelName, key, url, headers, respPath, reqTempl, msgFormat, sysRole, userRole, assRole
            )
        },
        onStartModelDownload = { settingsViewModel.startModelDownload() },
        onDeleteModel = { settingsViewModel.deleteModel() },
        onCancelModelDownload = { settingsViewModel.cancelModelDownload() },
        onStartTtsModelDownload = { settingsViewModel.startTtsModelDownload() },
        onDeleteTtsModel = { settingsViewModel.deleteTtsModel() },
        onCancelTtsModelDownload = { settingsViewModel.cancelTtsModelDownload() },
        onTranslationEnabledChange = onTranslationEnabledChange,
        onLanguageSelected = onLanguageSelected,
        onDownloadTranslationModel = { settingsViewModel.downloadTranslationModel(it) },
        onDeleteTranslationModel = { settingsViewModel.deleteTranslationModel(it) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    settingsViewModel: SettingsViewModel,
    initialYoutubeKey: String,
    initialEdgeTtsKey: String,
    initialChatKey: String,
    initialProvider: LlmProvider,
    initialModel: String,
    initialCustomUrl: String,
    initialCustomHeaders: String,
    initialCustomResponsePath: String,
    initialCustomRequestTemplate: String,
    initialCustomMessageFormat: String,
    initialCustomSystemRole: String,
    initialCustomUserRole: String,
    initialCustomAssistantRole: String,
    initialSttMode: SttMode,
    initialTtsMode: com.app.assistant.tts.TtsMode,
    isTtsInstalled: Boolean,
    verificationState: VerificationState,
    fetchedModelsMap: Map<LlmProvider, List<String>>,
    isFetchingModels: Boolean,
    modelFetchError: String?,
    isModelInstalled: Boolean,
    modelDownloadState: DownloadState,
    ttsDownloadState: DownloadState,
    isTranslationEnabled: Boolean,
    activeLanguageCode: String,
    downloadedTranslationLanguages: Set<String>,
    downloadingTranslationLanguages: Set<String>,
    translationLanguages: List<Pair<String, String>>,
    isDefaultAssistant: Boolean,
    onBack: () -> Unit,
    onResetVerificationState: () -> Unit,
    onFetchModelsForProvider: (LlmProvider, String, String, String) -> Unit,
    onVerifyModelAndSaveCapabilities: (
        LlmProvider, String, String, String, String, String, String, String, String, String, String
    ) -> Unit,
    onStartModelDownload: () -> Unit,
    onDeleteModel: () -> Unit,
    onCancelModelDownload: () -> Unit,
    onStartTtsModelDownload: () -> Unit,
    onDeleteTtsModel: () -> Unit,
    onCancelTtsModelDownload: () -> Unit,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onDownloadTranslationModel: (String) -> Unit,
    onDeleteTranslationModel: (String) -> Unit
) {
    var apiKey1 by rememberSaveable { mutableStateOf(initialYoutubeKey) }
    var edgeTtsKey by rememberSaveable { mutableStateOf(initialEdgeTtsKey) }
    var apiKey2 by rememberSaveable { mutableStateOf(initialChatKey) }
    var provider by rememberSaveable { mutableStateOf(initialProvider) }
    var model by rememberSaveable { mutableStateOf(initialModel) }
    var sttMode by rememberSaveable { mutableStateOf(initialSttMode) }
    var ttsMode by rememberSaveable { mutableStateOf(initialTtsMode) }

    var showQrScanner by remember { mutableStateOf(false) }
    var targetKeyField by remember { mutableStateOf<String?>(null) } // "chat", "youtube", "edge_tts"

    
    var customUrl by rememberSaveable { mutableStateOf(initialCustomUrl) }
    var customHeaders by rememberSaveable { mutableStateOf(initialCustomHeaders) }
    var customResponsePath by rememberSaveable { mutableStateOf(initialCustomResponsePath) }
    var customRequestTemplate by rememberSaveable { mutableStateOf(initialCustomRequestTemplate) }
    var customMessageFormat by rememberSaveable { mutableStateOf(initialCustomMessageFormat) }
    var customSystemRole by rememberSaveable { mutableStateOf(initialCustomSystemRole) }
    var customUserRole by rememberSaveable { mutableStateOf(initialCustomUserRole) }
    var customAssistantRole by rememberSaveable { mutableStateOf(initialCustomAssistantRole) }

    val isAutoDetecting by settingsViewModel.isAutoDetecting.collectAsState()
    val autoDetectStatus by settingsViewModel.autoDetectStatus.collectAsState()
    val autoDetectSuccess by settingsViewModel.autoDetectSuccess.collectAsState()

    var selectedPreset by rememberSaveable { mutableStateOf(CustomServerPreset.OPENAI_COMPATIBLE) }
    var presetDropdownExpanded by remember { mutableStateOf(false) }
    var showAdvancedCustomSettings by rememberSaveable { mutableStateOf(false) }

    var showTranslationSheet by remember { mutableStateOf(false) }



    LaunchedEffect(isTtsInstalled) {
        if (ttsMode == com.app.assistant.tts.TtsMode.OFFLINE) {
            if (!isTtsInstalled) {
                ttsMode = com.app.assistant.tts.TtsMode.NATIVE
                settingsViewModel.updateTtsMode(com.app.assistant.tts.TtsMode.NATIVE)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingsViewModel.checkDefaultAssistantStatus()
                settingsViewModel.refreshModelStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var expanded by remember { mutableStateOf(false) }

    var modelDropdownExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val versionName = remember(context) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    LaunchedEffect(Unit) {
        onResetVerificationState()
        val currentKey = initialChatKey
        if (currentKey.isNotBlank() || initialProvider == LlmProvider.OLLAMA || initialProvider == LlmProvider.OPEN_ROUTER) {
            onFetchModelsForProvider(
                initialProvider,
                currentKey,
                initialCustomUrl,
                initialCustomHeaders
            )
        }
    }

    BackHandler(enabled = true) {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = stringResource(id = R.string.settings_title),
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleLarge
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // CARD 1: AI MODEL SETTINGS
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsSectionHeader(
                        title = "Провайдер модели ИИ",
                        description = "Configure the main language model for chat",
                        icon = R.drawable.ic_settings
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = provider.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(id = R.string.llm_provider)) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_drop_down),
                                    contentDescription = null
                                )
                            }
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { expanded = true }
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LlmProvider.entries.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.displayName) },
                                    onClick = {
                                          if (provider != p) {
                                              provider = p
                                              settingsViewModel.updateLlmProvider(p)
                                              onResetVerificationState()
                                              val newKey = settingsViewModel.loadChatKey(p)
                                              apiKey2 = newKey
                                              if (p != LlmProvider.CUSTOM) {
                                                  if (newKey.isBlank() && p != LlmProvider.OLLAMA) {
                                                      model = ""
                                                      settingsViewModel.updateLlmModel("")
                                                  } else {
                                                      model = p.defaultModel
                                                      settingsViewModel.updateLlmModel(p.defaultModel)
                                                      onFetchModelsForProvider(p, newKey, "", "")
                                                  }
                                              } else {
                                                 model = p.defaultModel
                                                 settingsViewModel.updateLlmModel(p.defaultModel)
                                                 if (customUrl.isBlank()) {
                                                     customUrl = p.config.url
                                                     settingsViewModel.updateLlmCustomUrl(p.config.url)
                                                 }
                                                 if (customHeaders.isBlank()) {
                                                     customHeaders = "{\"Authorization\": \"Bearer {{API_KEY}}\", \"Content-Type\": \"application/json\"}"
                                                     settingsViewModel.updateLlmCustomHeaders(customHeaders)
                                                 }
                                                 if (customResponsePath.isBlank()) {
                                                     customResponsePath = p.config.responsePath
                                                     settingsViewModel.updateLlmCustomResponsePath(p.config.responsePath)
                                                 }
                                                 if (customRequestTemplate.isBlank()) {
                                                     customRequestTemplate = p.config.requestTemplate
                                                     settingsViewModel.updateLlmCustomRequestTemplate(p.config.requestTemplate)
                                                 }
                                                 if (customMessageFormat.isBlank()) {
                                                     customMessageFormat = p.config.messageFormat
                                                     settingsViewModel.updateLlmCustomMessageFormat(p.config.messageFormat)
                                                 }
                                                 if (customSystemRole.isBlank()) {
                                                     customSystemRole = p.config.systemRole ?: "system"
                                                     settingsViewModel.updateLlmCustomSystemRole(customSystemRole)
                                                 }
                                                 if (customUserRole.isBlank()) {
                                                     customUserRole = p.config.userRole
                                                     settingsViewModel.updateLlmCustomUserRole(p.config.userRole)
                                                 }
                                                 if (customAssistantRole.isBlank()) {
                                                     customAssistantRole = p.config.assistantRole
                                                     settingsViewModel.updateLlmCustomAssistantRole(p.config.assistantRole)
                                                 }
                                              }
                                           }
                                           expanded = false
                                      }
                                  )
                              }
                          }
                      }
  
                      Spacer(modifier = Modifier.height(12.dp))

                      OutlinedTextField(
                          value = apiKey2,
                          onValueChange = {
                              apiKey2 = it
                              settingsViewModel.updateChatApiKey(it, provider)
                              onResetVerificationState()
                              if (it.isNotBlank()) {
                                  if (model.isBlank()) {
                                      model = provider.defaultModel
                                      settingsViewModel.updateLlmModel(provider.defaultModel)
                                  }
                                  onFetchModelsForProvider(provider, it, customUrl, customHeaders)
                              }
                          },
                          label = { Text(if (provider == LlmProvider.CUSTOM) "Chat API Key (Optional for local servers)" else stringResource(id = R.string.chat_api_key)) },
                          visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                          trailingIcon = {
                              IconButton(
                                  onClick = {
                                      targetKeyField = "chat"
                                      showQrScanner = true
                                  }
                              ) {
                                  Icon(
                                      painter = painterResource(id = R.drawable.ic_qr_code_scanner),
                                      contentDescription = "Сканировать API-ключ чата"
                                  )
                              }
                          },
                          shape = RoundedCornerShape(12.dp),
                          modifier = Modifier.fillMaxWidth()
                      )

                      Spacer(modifier = Modifier.height(12.dp))

                      val isKeyMissing = apiKey2.isBlank() && provider != LlmProvider.OLLAMA && provider != LlmProvider.CUSTOM
                      val modelsList = fetchedModelsMap[provider] ?: provider.suggestedModels
  
                      Box(modifier = Modifier.fillMaxWidth()) {
                          OutlinedTextField(
                              value = model,
                              onValueChange = { 
                                  model = it
                                  settingsViewModel.updateLlmModel(it)
                                  onResetVerificationState()
                              },
                              label = { Text(stringResource(id = R.string.llm_model)) },
                              placeholder = if (isKeyMissing) {
                                  { Text("Введите API-ключ, чтобы выбрать модель") }
                              } else null,
                              shape = RoundedCornerShape(12.dp),
                              modifier = Modifier.fillMaxWidth(),
                              supportingText = if (isKeyMissing) {
                                  { Text("Введите API-ключ ${provider.displayName}, чтобы увидеть модели", color = MaterialTheme.colorScheme.error) }
                              } else if (!modelFetchError.isNullOrBlank()) {
                                  { Text(modelFetchError, color = MaterialTheme.colorScheme.error) }
                              } else null,
                              trailingIcon = {
                                  IconButton(onClick = { modelDropdownExpanded = !modelDropdownExpanded }) {
                                      Icon(
                                          painter = painterResource(id = R.drawable.ic_arrow_drop_down),
                                          contentDescription = "Показать рекомендуемые модели"
                                      )
                                  }
                              }
                          )
                          DropdownMenu(
                              expanded = modelDropdownExpanded,
                              onDismissRequest = { modelDropdownExpanded = false },
                              modifier = Modifier.fillMaxWidth()
                          ) {
                              if (isKeyMissing) {
                                  DropdownMenuItem(
                                      text = {
                                          Row(
                                              verticalAlignment = Alignment.CenterVertically,
                                              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                          ) {
                                              Icon(
                                                  imageVector = Icons.Default.Info,
                                                  contentDescription = "Нужен API-ключ",
                                                  tint = MaterialTheme.colorScheme.primary,
                                                  modifier = Modifier.size(20.dp)
                                              )
                                              Spacer(modifier = Modifier.width(8.dp))
                                              Text(
                                                  text = "Сначала введите API-ключ ${provider.displayName}, чтобы увидеть доступные модели.",
                                                  style = MaterialTheme.typography.bodyMedium,
                                                  color = MaterialTheme.colorScheme.onSurface
                                              )
                                          }
                                      },
                                      onClick = { modelDropdownExpanded = false }
                                  )
                              } else if (isFetchingModels) {
                                  DropdownMenuItem(
                                      text = { 
                                          Row(
                                              verticalAlignment = Alignment.CenterVertically,
                                              modifier = Modifier.fillMaxWidth()
                                          ) {
                                              CircularProgressIndicator(
                                                  modifier = Modifier.size(16.dp),
                                                  strokeWidth = 2.dp
                                              )
                                              Spacer(modifier = Modifier.width(12.dp))
                                              Text("Загружаю модели…")
                                          }
                                      },
                                      onClick = {}
                                  )
                              } else {
                                  val canFetch = provider != LlmProvider.CUSTOM || customUrl.isNotBlank()
                                  if (canFetch) {
                                      DropdownMenuItem(
                                          text = {
                                              Row(
                                                  verticalAlignment = Alignment.CenterVertically,
                                                  modifier = Modifier.fillMaxWidth()
                                              ) {
                                                  Icon(
                                                      painter = painterResource(id = R.drawable.ic_restart),
                                                      contentDescription = "Загрузить список",
                                                      modifier = Modifier.size(16.dp)
                                                  )
                                                  Spacer(modifier = Modifier.width(12.dp))
                                                  Text("Загрузить модели с сервера")
                                              }
                                          },
                                          onClick = {
                                              onFetchModelsForProvider(
                                                  provider,
                                                  apiKey2,
                                                  customUrl,
                                                  customHeaders
                                              )
                                          }
                                      )
                                      if (modelsList.isNotEmpty()) {
                                          androidx.compose.material3.HorizontalDivider(
                                              modifier = Modifier.padding(vertical = 4.dp)
                                          )
                                      }
                                  }
  
                                  if (modelsList.isNotEmpty()) {
                                      modelsList.forEach { suggested ->
                                          DropdownMenuItem(
                                              text = { Text(suggested) },
                                              onClick = {
                                                  model = suggested
                                                  settingsViewModel.updateLlmModel(suggested)
                                                  onResetVerificationState()
                                                  modelDropdownExpanded = false
                                              }
                                          )
                                      }
                                  } else if (provider == LlmProvider.CUSTOM) {
                                      DropdownMenuItem(
                                          text = { Text("Модели ещё не загружены. Нажмите «Загрузить модели с сервера» или введите название вручную.") },
                                          onClick = {
                                              if (customUrl.isNotBlank()) {
                                                  onFetchModelsForProvider(
                                                      provider,
                                                      apiKey2,
                                                      customUrl,
                                                      customHeaders
                                                  )
                                              } else {
                                                  modelDropdownExpanded = false
                                              }
                                          }
                                      )
                                  } else {
                                      DropdownMenuItem(
                                          text = { Text("Модели ещё не загружены. Нажмите «Загрузить модели с сервера».") },
                                          onClick = {
                                              onFetchModelsForProvider(
                                                  provider,
                                                  apiKey2,
                                                  customUrl,
                                                  customHeaders
                                              )
                                          }
                                      )
                                  }
                              }
                          }
                      }

                      Spacer(modifier = Modifier.height(16.dp))
  
                      // Verification Section
                      VerifySection(
                          verificationState = verificationState,
                          onVerify = {
                              onVerifyModelAndSaveCapabilities(
                                  provider,
                                  model,
                                  apiKey2,
                                  customUrl,
                                  customHeaders,
                                  customResponsePath,
                                  customRequestTemplate,
                                  customMessageFormat,
                                  customSystemRole,
                                  customUserRole,
                                  customAssistantRole
                              )
                          }
                      )
                }
            }

            // CARD 2: CUSTOM PROVIDER DETAILS (ONLY RENDERED IF CUSTOM SELECTED)
            if (provider == LlmProvider.CUSTOM) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingsSectionHeader(
                            title = "Настройка своего сервера",
                            description = "Define custom API mappings and endpoints",
                            icon = R.drawable.ic_settings
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // API Endpoint URL
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { 
                                customUrl = it
                                settingsViewModel.updateLlmCustomUrl(it)
                                settingsViewModel.clearAutoDetectStatus()
                                onResetVerificationState()
                            },
                            label = { Text("URL API") },
                            placeholder = { Text("например, http://192.168.1.3:11434") },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Auto-Detect & Fetch Models Button
                        Button(
                            onClick = {
                                settingsViewModel.autoDetectAndApplyConfig(
                                    baseUrl = customUrl,
                                    apiKey = apiKey2,
                                    onApplied = { detected ->
                                        customUrl = detected.completionUrl
                                        val headersJson = if (detected.headers.isNotEmpty()) {
                                            val entries = detected.headers.map { "\"${it.key}\": \"${it.value.replace("\"", "\\\"")}\"" }
                                            "{${entries.joinToString(", ")}}"
                                        } else "{}"
                                        customHeaders = headersJson
                                        customResponsePath = detected.responsePath
                                        customRequestTemplate = detected.requestTemplate
                                        customMessageFormat = detected.messageFormat
                                        customSystemRole = detected.systemRole ?: ""
                                        customUserRole = detected.userRole
                                        customAssistantRole = detected.assistantRole
                                        if (detected.availableModels.isNotEmpty()) {
                                            model = detected.availableModels.first()
                                        }
                                        selectedPreset = if (detected.serverType == "Ollama Native") {
                                            CustomServerPreset.OLLAMA_NATIVE
                                        } else {
                                            CustomServerPreset.OPENAI_COMPATIBLE
                                        }
                                    }
                                )
                            },
                            enabled = !isAutoDetecting && customUrl.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isAutoDetecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Определяю сервер…")
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_restart),
                                    contentDescription = "Автоопределение",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Определить сервер и модели автоматически", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // Auto-Detect Status Banner
                        if (!autoDetectStatus.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (autoDetectSuccess == true) {
                                        Color(0xFF4CAF50).copy(alpha = 0.1f)
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                    }
                                ),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (autoDetectSuccess == true) Color(0xFF4CAF50).copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (autoDetectSuccess == true) Icons.Default.Check else Icons.Default.Info,
                                        contentDescription = null,
                                        tint = if (autoDetectSuccess == true) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = autoDetectStatus ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Preset Selector
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedPreset.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Шаблон совместимости сервера") },
                                supportingText = { Text(selectedPreset.description) },
                                trailingIcon = {
                                    IconButton(onClick = { presetDropdownExpanded = !presetDropdownExpanded }) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_drop_down),
                                            contentDescription = "Выберите шаблон"
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            DropdownMenu(
                                expanded = presetDropdownExpanded,
                                onDismissRequest = { presetDropdownExpanded = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CustomServerPreset.entries.forEach { p ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(p.displayName, fontWeight = FontWeight.SemiBold)
                                                Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        },
                                        onClick = {
                                            selectedPreset = p
                                            presetDropdownExpanded = false
                                            settingsViewModel.applyCustomPreset(
                                                preset = p,
                                                currentUrl = customUrl,
                                                apiKey = apiKey2,
                                                onApplied = { url, headers, respPath, template, format, sysRole, userRole, assRole ->
                                                    customUrl = url
                                                    customHeaders = headers
                                                    customResponsePath = respPath
                                                    customRequestTemplate = template
                                                    customMessageFormat = format
                                                    customSystemRole = sysRole
                                                    customUserRole = userRole
                                                    customAssistantRole = assRole
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Collapsible Section for Advanced Manual Settings
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAdvancedCustomSettings = !showAdvancedCustomSettings }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Расширенная настройка запроса и ролей",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (showAdvancedCustomSettings) "Tap to collapse manual configuration" else "Only needed for non-standard custom APIs",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    painter = painterResource(id = if (showAdvancedCustomSettings) R.drawable.ic_up_arrow else R.drawable.ic_arrow_drop_down),
                                    contentDescription = if (showAdvancedCustomSettings) "Collapse" else "Expand",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        AnimatedVisibility(visible = showAdvancedCustomSettings) {
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                OutlinedTextField(
                                    value = customHeaders,
                                    onValueChange = { 
                                        customHeaders = it
                                        settingsViewModel.updateLlmCustomHeaders(it)
                                        selectedPreset = CustomServerPreset.CUSTOM
                                        onResetVerificationState()
                                    },
                                    label = { Text("Свои заголовки (JSON)") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = customResponsePath,
                                    onValueChange = { 
                                        customResponsePath = it
                                        settingsViewModel.updateLlmCustomResponsePath(it)
                                        selectedPreset = CustomServerPreset.CUSTOM
                                        onResetVerificationState()
                                    },
                                    label = { Text("Путь к ответу (JSON)") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = customRequestTemplate,
                                    onValueChange = { 
                                        customRequestTemplate = it
                                        settingsViewModel.updateLlmCustomRequestTemplate(it)
                                        selectedPreset = CustomServerPreset.CUSTOM
                                        onResetVerificationState()
                                    },
                                    label = { Text("Шаблон тела запроса (JSON)") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp),
                                    maxLines = 10
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = customMessageFormat,
                                    onValueChange = { 
                                        customMessageFormat = it
                                        settingsViewModel.updateLlmCustomMessageFormat(it)
                                        selectedPreset = CustomServerPreset.CUSTOM
                                        onResetVerificationState()
                                    },
                                    label = { Text("Формат сообщения (JSON)") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = customSystemRole,
                                    onValueChange = { 
                                        customSystemRole = it
                                        settingsViewModel.updateLlmCustomSystemRole(it)
                                        selectedPreset = CustomServerPreset.CUSTOM
                                        onResetVerificationState()
                                    },
                                    label = { Text("Системная роль") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = customUserRole,
                                    onValueChange = { 
                                        customUserRole = it
                                        settingsViewModel.updateLlmCustomUserRole(it)
                                        selectedPreset = CustomServerPreset.CUSTOM
                                        onResetVerificationState()
                                    },
                                    label = { Text("Роль пользователя") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = customAssistantRole,
                                    onValueChange = { 
                                        customAssistantRole = it
                                        settingsViewModel.updateLlmCustomAssistantRole(it)
                                        selectedPreset = CustomServerPreset.CUSTOM
                                        onResetVerificationState()
                                    },
                                    label = { Text("Роль ассистента") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            // CARD 3: INTEGRATIONS
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsSectionHeader(
                        title = "Интеграции",
                        description = "Manage credentials for third-party integrations",
                        icon = R.drawable.ic_settings
                    )
                    Spacer(modifier = Modifier.height(8.dp))
 
                    OutlinedTextField(
                        value = apiKey1,
                        onValueChange = { 
                            apiKey1 = it
                            settingsViewModel.updateYoutubeApiKey(it)
                        },
                        label = { Text(stringResource(id = R.string.youtube_api_key)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    targetKeyField = "youtube"
                                    showQrScanner = true
                                }
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_qr_code_scanner),
                                    contentDescription = "Сканировать API-ключ YouTube"
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // CARD 4: SPEECH TO TEXT SETTINGS
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsSectionHeader(
                        title = stringResource(id = R.string.stt_settings_title),
                        description = "Configure how the assistant listens to your speech",
                        icon = R.drawable.ic_mic
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val isInstalled = isModelInstalled
                    val downloadState = modelDownloadState

                    // Option 1: Native STT
                    SelectableOptionCard(
                        selected = sttMode == SttMode.NATIVE,
                        onClick = {
                            sttMode = SttMode.NATIVE
                            settingsViewModel.updateSttMode(SttMode.NATIVE)
                        },
                        title = stringResource(id = R.string.use_native_stt),
                        description = stringResource(id = R.string.native_stt_desc)
                    )

                    // Option 2: Parakeet STT (Only enabled if downloaded)
                    SelectableOptionCard(
                        selected = sttMode == SttMode.PARAKEET,
                        enabled = isInstalled,
                        onClick = {
                            sttMode = SttMode.PARAKEET
                            settingsViewModel.updateSttMode(SttMode.PARAKEET)
                        },
                        title = stringResource(id = R.string.use_parakeet_stt),
                        description = if (isInstalled) {
                            stringResource(id = R.string.parakeet_stt_desc_enabled)
                        } else {
                            stringResource(id = R.string.parakeet_stt_desc_disabled)
                        }
                    )

                    // Option 3: Hybrid STT (Only enabled if downloaded)
                    SelectableOptionCard(
                        selected = sttMode == SttMode.HYBRID,
                        enabled = isInstalled,
                        onClick = {
                            sttMode = SttMode.HYBRID
                            settingsViewModel.updateSttMode(SttMode.HYBRID)
                        },
                        title = stringResource(id = R.string.use_hybrid_stt),
                        description = if (isInstalled) {
                            stringResource(id = R.string.hybrid_stt_desc_enabled)
                        } else {
                            stringResource(id = R.string.hybrid_stt_desc_disabled)
                        }
                    )

                    // Option 4: API STT (Cloud)
                    SelectableOptionCard(
                        selected = sttMode == SttMode.API,
                        onClick = {
                            sttMode = SttMode.API
                            settingsViewModel.updateSttMode(SttMode.API)
                        },
                        title = stringResource(id = R.string.use_api_stt),
                        description = stringResource(id = R.string.api_stt_desc) + " (Configured: ${SpeechConfig.ACTIVE_STT_PROVIDER})"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Model Information & Warning Card
                    ModelDownloadCard(
                        title = stringResource(id = R.string.parakeet_model_title),
                        description = stringResource(id = R.string.parakeet_model_desc),
                        isInstalled = isInstalled,
                        downloadState = downloadState,
                        onDownload = onStartModelDownload,
                        onDelete = onDeleteModel,
                        onCancel = onCancelModelDownload,
                        onClear = onDeleteModel,
                        onRetry = onStartModelDownload,
                        installedText = stringResource(id = R.string.status_installed),
                        notDownloadedText = stringResource(id = R.string.status_not_downloaded),
                        deleteButtonText = stringResource(id = R.string.delete_model),
                        downloadButtonText = stringResource(id = R.string.download_model)
                    )

                    // Automatically revert selection to NATIVE if model is not installed but current selected is parakeet/hybrid
                    LaunchedEffect(isInstalled) {
                        if (!isInstalled && (sttMode == SttMode.PARAKEET || sttMode == SttMode.HYBRID)) {
                            sttMode = SttMode.NATIVE
                            settingsViewModel.updateSttMode(SttMode.NATIVE)
                        }
                    }
                }
            }

            // CARD 5: TEXT TO SPEECH (TTS) SETTINGS
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsSectionHeader(
                        title = "Настройки синтеза речи",
                        description = "Configure how the assistant speaks back to you",
                        icon = R.drawable.ic_speaker
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Офлайн-модель синтеза речи занимает место на устройстве.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Option 1: Native TTS
                    SelectableOptionCard(
                        selected = ttsMode == com.app.assistant.tts.TtsMode.NATIVE,
                        onClick = {
                            ttsMode = com.app.assistant.tts.TtsMode.NATIVE
                            settingsViewModel.updateTtsMode(com.app.assistant.tts.TtsMode.NATIVE)
                        },
                        title = "Системный синтез речи (быстро)",
                        description = "Uses the built-in system text-to-speech engine.",
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    try {
                                        val intent = android.content.Intent("com.android.settings.TTS_SETTINGS")
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Не удалось открыть настройки синтеза речи",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_settings),
                                    contentDescription = "Открыть системные настройки синтеза речи",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // Option 2: Offline TTS
                    SelectableOptionCard(
                        selected = ttsMode == com.app.assistant.tts.TtsMode.OFFLINE,
                        enabled = isTtsInstalled,
                        onClick = {
                            ttsMode = com.app.assistant.tts.TtsMode.OFFLINE
                            settingsViewModel.updateTtsMode(com.app.assistant.tts.TtsMode.OFFLINE)
                        },
                        title = "Офлайн-модель синтеза речи",
                        description = if (isTtsInstalled) {
                            "Русские голоса (Piper/ONNX) работают на устройстве без интернета."
                        } else {
                            "Русские голоса (Piper/ONNX) работают без интернета. Сначала скачайте хотя бы один голос ниже."
                        }
                    )

                    // Option 3: API TTS (Cloud)
                    SelectableOptionCard(
                        selected = ttsMode == com.app.assistant.tts.TtsMode.API,
                        onClick = {
                            ttsMode = com.app.assistant.tts.TtsMode.API
                            settingsViewModel.updateTtsMode(com.app.assistant.tts.TtsMode.API)
                        },
                        title = stringResource(id = R.string.use_api_tts),
                        description = stringResource(id = R.string.api_tts_desc) + " (Configured: ${SpeechConfig.ACTIVE_TTS_PROVIDER})"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = edgeTtsKey,
                        onValueChange = { 
                            edgeTtsKey = it
                            settingsViewModel.updateEdgeTtsSubscriptionKey(it)
                        },
                        label = { Text(stringResource(id = R.string.edge_tts_subscription_key)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    targetKeyField = "edge_tts"
                                    showQrScanner = true
                                }
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_qr_code_scanner),
                                    contentDescription = "Сканировать ключ Edge TTS"
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OfflineVoicesSection(settingsViewModel)
                }
            }

            //Hiding for now due to complexity of response stream its difficult to implement offline translation, but it will be added in future for sure
            // CARD 6: OFFLINE TRANSLATION SETTINGS
//            Card(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(vertical = 8.dp),
//                shape = RoundedCornerShape(16.dp),
//                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
//                colors = CardDefaults.cardColors(
//                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
//                ),
//                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
//            ) {
//                Column(modifier = Modifier.padding(16.dp)) {
//                    SettingsSectionHeader(
//                        title = "Настройки офлайн-перевода",
//                        description = "Translate queries and responses offline using Google ML Kit models.",
//                        icon = R.drawable.ic_translate
//                    )
//                    Spacer(modifier = Modifier.height(8.dp))
//
//                    Row(
//                        modifier = Modifier
//                            .fillMaxWidth()
//                            .padding(vertical = 8.dp),
//                        verticalAlignment = Alignment.CenterVertically
//                    ) {
//                        Column(modifier = Modifier.weight(1f)) {
//                            Text(
//                                text = "Включить перевод",
//                                style = MaterialTheme.typography.bodyLarge,
//                                fontWeight = FontWeight.Bold
//                            )
//                            Text(
//                                text = "Переводить сообщения чата и голосовой ввод",
//                                style = MaterialTheme.typography.bodySmall,
//                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
//                            )
//                        }
//                        Switch(
//                            checked = isTranslationEnabled,
//                            onCheckedChange = onTranslationEnabledChange
//                        )
//                    }
//
//                    if (isTranslationEnabled) {
//                        androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
//
//                        val activeLangName = remember(activeLanguageCode, translationLanguages) {
//                            translationLanguages.find { it.second == activeLanguageCode }?.first?.let { formatLanguageName(it) } ?: activeLanguageCode.uppercase()
//                        }
//
//                        Card(
//                            onClick = { showTranslationSheet = true },
//                            modifier = Modifier
//                                .fillMaxWidth()
//                                .padding(vertical = 6.dp),
//                            shape = RoundedCornerShape(12.dp),
//                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
//                            colors = CardDefaults.cardColors(
//                                containerColor = MaterialTheme.colorScheme.surface
//                            )
//                        ) {
//                            Row(
//                                modifier = Modifier
//                                    .fillMaxWidth()
//                                    .padding(14.dp),
//                                verticalAlignment = Alignment.CenterVertically
//                            ) {
//                                Icon(
//                                    painter = painterResource(id = R.drawable.ic_translate),
//                                    contentDescription = null,
//                                    tint = MaterialTheme.colorScheme.primary,
//                                    modifier = Modifier.size(24.dp)
//                                )
//                                Spacer(modifier = Modifier.width(16.dp))
//                                Column(modifier = Modifier.weight(1f)) {
//                                    Text(
//                                        text = "Язык по умолчанию",
//                                        style = MaterialTheme.typography.bodyLarge,
//                                        fontWeight = FontWeight.Bold,
//                                        color = MaterialTheme.colorScheme.onSurface
//                                    )
//                                    Spacer(modifier = Modifier.height(2.dp))
//                                    Text(
//                                        text = "Выбран: $activeLangName",
//                                        style = MaterialTheme.typography.bodySmall,
//                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
//                                    )
//                                }
//                                Icon(
//                                    painter = painterResource(id = R.drawable.ic_arrow_drop_down),
//                                    contentDescription = "Выберите язык",
//                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
//                                    modifier = Modifier.size(24.dp)
//                                )
//                            }
//                        }
//
//                        Spacer(modifier = Modifier.height(8.dp))
//
//                        Button(
//                            onClick = { showTranslationSheet = true },
//                            shape = RoundedCornerShape(8.dp),
//                            modifier = Modifier.fillMaxWidth().height(40.dp)
//                        ) {
//                            Text("Управление моделями перевода", style = MaterialTheme.typography.labelMedium)
//                        }
//                    }
//                }
//            }

            // CARD 7: DEFAULT ASSISTANT SETTINGS
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsSectionHeader(
                        title = stringResource(id = R.string.default_assistant_title),
                        description = stringResource(id = R.string.default_assistant_desc),
                        icon = R.drawable.ic_settings
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isDefaultAssistant) {
                                    stringResource(id = R.string.default_assistant_status_active)
                                } else {
                                    stringResource(id = R.string.default_assistant_status_inactive)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isDefaultAssistant) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                        if (isDefaultAssistant) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Активна",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Неактивна",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            openAssistantSettings(context)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                    ) {
                        Text(
                            text = if (isDefaultAssistant) {
                                stringResource(id = R.string.default_assistant_btn_open_settings)
                            } else {
                                stringResource(id = R.string.default_assistant_btn_set)
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(id = R.string.app_version, versionName),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }

    if (showTranslationSheet) {
        val sheetState = rememberModalBottomSheetState()
        TranslationLanguageManagerBottomSheet(
            sheetState = sheetState,
            languages = translationLanguages,
            activeLanguageCode = activeLanguageCode,
            downloadedLanguages = downloadedTranslationLanguages,
            downloadingLanguages = downloadingTranslationLanguages,
            onLanguageSelected = {
                onLanguageSelected(it)
            },
            onDownloadLanguage = {
                onDownloadTranslationModel(it)
            },
            onDeleteLanguage = {
                onDeleteTranslationModel(it)
            },
            onDismissRequest = { showTranslationSheet = false }
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onDismissRequest = {
                showQrScanner = false
                targetKeyField = null
            },
            onCodeScanned = { rawCode ->
                showQrScanner = false
                val trimmed = rawCode.trim()
                try {
                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        val jsonObject = org.json.JSONObject(trimmed)
                        val importedKeys = mutableListOf<String>()
                        
                        val chatKeys = listOf("chat_api_key", "chatApiKey", "gemini_api_key", "groq_api_key", "api_key", "apiKey", "gemini_key", "groq_key")
                        for (key in chatKeys) {
                            if (jsonObject.has(key)) {
                                val value = jsonObject.getString(key)
                                if (value.isNotBlank()) {
                                    apiKey2 = value
                                    settingsViewModel.updateChatApiKey(value, provider)
                                    onResetVerificationState()
                                    if (model.isBlank()) {
                                        model = provider.defaultModel
                                        settingsViewModel.updateLlmModel(provider.defaultModel)
                                    }
                                    onFetchModelsForProvider(provider, value, customUrl, customHeaders)
                                    importedKeys.add("Chat API Key")
                                }
                                break
                            }
                        }
                        
                        val youtubeKeys = listOf("youtube_api_key", "youtubeApiKey", "youtube_key", "youtubeKey")
                        for (key in youtubeKeys) {
                            if (jsonObject.has(key)) {
                                val value = jsonObject.getString(key)
                                if (value.isNotBlank()) {
                                    apiKey1 = value
                                    settingsViewModel.updateYoutubeApiKey(value)
                                    importedKeys.add("YouTube API Key")
                                }
                                break
                            }
                        }
                        
                        val ttsKeys = listOf("edge_tts_key", "edgeTtsKey", "edge_tts_subscription_key", "tts_key", "ttsKey")
                        for (key in ttsKeys) {
                            if (jsonObject.has(key)) {
                                val value = jsonObject.getString(key)
                                if (value.isNotBlank()) {
                                    edgeTtsKey = value
                                    settingsViewModel.updateEdgeTtsSubscriptionKey(value)
                                    importedKeys.add("Edge TTS Key")
                                }
                                break
                            }
                        }
                        
                        if (importedKeys.isNotEmpty()) {
                            android.widget.Toast.makeText(
                                context,
                                "Импортировано: ${importedKeys.joinToString(", ")}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                "QR-код распознан, но подходящих ключей в JSON нет",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        if (trimmed.isNotBlank()) {
                            when (targetKeyField) {
                                "chat" -> {
                                    apiKey2 = trimmed
                                    settingsViewModel.updateChatApiKey(trimmed, provider)
                                    onResetVerificationState()
                                    if (model.isBlank()) {
                                        model = provider.defaultModel
                                        settingsViewModel.updateLlmModel(provider.defaultModel)
                                    }
                                    onFetchModelsForProvider(provider, trimmed, customUrl, customHeaders)
                                    android.widget.Toast.makeText(context, "API-ключ чата импортирован", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                "youtube" -> {
                                    apiKey1 = trimmed
                                    settingsViewModel.updateYoutubeApiKey(trimmed)
                                    android.widget.Toast.makeText(context, "API-ключ YouTube импортирован", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                "edge_tts" -> {
                                    edgeTtsKey = trimmed
                                    settingsViewModel.updateEdgeTtsSubscriptionKey(trimmed)
                                    android.widget.Toast.makeText(context, "Ключ Edge TTS импортирован", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (trimmed.isNotBlank()) {
                        when (targetKeyField) {
                            "chat" -> {
                                apiKey2 = trimmed
                                settingsViewModel.updateChatApiKey(trimmed, provider)
                                onResetVerificationState()
                                if (model.isBlank()) {
                                    model = provider.defaultModel
                                    settingsViewModel.updateLlmModel(provider.defaultModel)
                                }
                                onFetchModelsForProvider(provider, trimmed, customUrl, customHeaders)
                                android.widget.Toast.makeText(context, "API-ключ чата импортирован", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            "youtube" -> {
                                apiKey1 = trimmed
                                settingsViewModel.updateYoutubeApiKey(trimmed)
                                android.widget.Toast.makeText(context, "API-ключ YouTube импортирован", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            "edge_tts" -> {
                                edgeTtsKey = trimmed
                                settingsViewModel.updateEdgeTtsSubscriptionKey(trimmed)
                                android.widget.Toast.makeText(context, "Ключ Edge TTS импортирован", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                targetKeyField = null
            }
        )
    }
}

@Composable
fun VerifySection(
    verificationState: VerificationState,
    onVerify: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        when (verificationState) {
            is VerificationState.Idle -> {
                Button(
                    onClick = onVerify,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Проверить и определить возможности", fontWeight = FontWeight.Bold)
                }
            }
            is VerificationState.Verifying -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Проверяю соединение и возможности…",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            is VerificationState.Success -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Успешно",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Проверка успешна!",
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Возможности модели:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CapabilityRow("Text Completion Support", true)
                            CapabilityRow("Vision / Image Support", verificationState.capabilities.hasImageInput)
                            CapabilityRow("Audio Support", verificationState.capabilities.hasAudioInput)
                            CapabilityRow("Video Support", verificationState.capabilities.hasVideoInput)
                            CapabilityRow("Document Support (PDF/TXT)", verificationState.capabilities.hasDocumentInput)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = onVerify,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Проверить настройки заново", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            is VerificationState.Error -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Ошибка",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Проверка не удалась",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = verificationState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onVerify,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Повторить проверку", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CapabilityRow(label: String, supported: Boolean) {
    val isDark = isSystemInDarkTheme()
    val tintColor = if (supported) {
        if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (supported) Icons.Default.Check else Icons.Default.Close,
            contentDescription = if (supported) "Supported" else "Unsupported",
            tint = tintColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun SettingsSectionHeader(
    title: String,
    description: String? = null,
    icon: Int? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (icon != null) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (description != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = if (icon != null) 32.dp else 0.dp)
            )
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    isInstalled: Boolean,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val containerColor = if (isInstalled) {
        if (isDark) Color(0xFF1B5E20).copy(alpha = 0.25f) else Color(0xFFE8F5E9)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val contentColor = if (isInstalled) {
        if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    }
    val borderColor = if (isInstalled) {
        if (isDark) Color(0xFF81C784).copy(alpha = 0.3f) else Color(0xFFC8E6C9)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor),
        color = containerColor,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isInstalled) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

@Composable
fun SelectableOptionCard(
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    title: String,
    description: String,
    trailingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val alpha = if (enabled) 1.0f else 0.5f
    
    val borderStroke = if (selected && enabled) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
    
    val containerColor = if (selected && enabled) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.15f else 0.08f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = borderStroke,
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                enabled = enabled,
                onClick = if (enabled) onClick else null
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                )
                if (description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.8f)
                    )
                }
            }
            if (trailingContent != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailingContent()
            }
        }
    }
}

@Composable
fun ModelDownloadCard(
    title: String,
    description: String,
    isInstalled: Boolean,
    downloadState: DownloadState,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    installedText: String,
    notDownloadedText: String,
    deleteButtonText: String,
    downloadButtonText: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (downloadState) {
                is DownloadState.Idle -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusBadge(
                            text = if (isInstalled) installedText else notDownloadedText,
                            isInstalled = isInstalled
                        )
                        if (isInstalled) {
                            OutlinedButton(
                                onClick = onDelete,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(deleteButtonText, style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            Button(
                                onClick = onDownload,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(downloadButtonText, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                is DownloadState.Downloading -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val progressPercent = (downloadState.progress * 100).toInt()
                            val downloadedMb = (downloadState.currentBytes.toDouble() / (1024 * 1024)).toInt()
                            val totalMb = (downloadState.totalBytes.toDouble() / (1024 * 1024)).toInt()
                            val progressText = if (totalMb > 0) {
                                "Downloading… $progressPercent% ($downloadedMb MB / $totalMb MB)"
                            } else {
                                "Downloading…"
                            }
                            Text(
                                text = progressText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Отмена",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { downloadState.progress },
                            modifier = Modifier.fillMaxWidth(),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                }
                is DownloadState.Completed -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusBadge(
                            text = "Загрузка завершена!",
                            isInstalled = true
                        )
                        OutlinedButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(deleteButtonText, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                is DownloadState.Error -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Ошибка загрузки: ${downloadState.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = onClear,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Очистить", style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = onRetry,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Повторить", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenContentPreview() {
    AssistantTheme {
        val context = LocalContext.current
        val dummyViewModel = remember {
            SettingsViewModel(
                settingsRepository = com.app.assistant.repository.SettingsRepository(context),
                okHttpClient = okhttp3.OkHttpClient()
            )
        }
        SettingsScreenContent(
            settingsViewModel = dummyViewModel,
            initialYoutubeKey = "AIzaSy...",
            initialEdgeTtsKey = "EdgeKey...",
            initialChatKey = "gsk_...",
            initialProvider = LlmProvider.GROQ,
            initialModel = LlmProvider.GROQ.defaultModel,
            initialCustomUrl = "",
            initialCustomHeaders = "",
            initialCustomResponsePath = "",
            initialCustomRequestTemplate = "",
            initialCustomMessageFormat = "",
            initialCustomSystemRole = "system",
            initialCustomUserRole = "user",
            initialCustomAssistantRole = "assistant",
            initialSttMode = SttMode.NATIVE,
            initialTtsMode = com.app.assistant.tts.TtsMode.NATIVE,
            isTtsInstalled = false,
            verificationState = VerificationState.Idle,
            fetchedModelsMap = emptyMap(),
            isFetchingModels = false,
            modelFetchError = null,
            isModelInstalled = false,
            modelDownloadState = DownloadState.Idle,
            ttsDownloadState = DownloadState.Idle,
            isTranslationEnabled = false,
            activeLanguageCode = "en",
            downloadedTranslationLanguages = emptySet(),
            downloadingTranslationLanguages = emptySet(),
            translationLanguages = emptyList(),
            isDefaultAssistant = false,
            onBack = {},
            onResetVerificationState = {},
            onFetchModelsForProvider = { _, _, _, _ -> },
            onVerifyModelAndSaveCapabilities = { _, _, _, _, _, _, _, _, _, _, _ -> },
            onStartModelDownload = {},
            onDeleteModel = {},
            onCancelModelDownload = {},
            onStartTtsModelDownload = {},
            onDeleteTtsModel = {},
            onCancelTtsModelDownload = {},
            onTranslationEnabledChange = {},
            onLanguageSelected = {},
            onDownloadTranslationModel = {},
            onDeleteTranslationModel = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenContentCustomPreview() {
    AssistantTheme {
        val context = LocalContext.current
        val dummyViewModel = remember {
            SettingsViewModel(
                settingsRepository = com.app.assistant.repository.SettingsRepository(context),
                okHttpClient = okhttp3.OkHttpClient()
            )
        }
        SettingsScreenContent(
            settingsViewModel = dummyViewModel,
            initialYoutubeKey = "",
            initialEdgeTtsKey = "",
            initialChatKey = "",
            initialProvider = LlmProvider.CUSTOM,
            initialModel = "my-custom-model",
            initialCustomUrl = "https://my-custom-endpoint.com/v1",
            initialCustomHeaders = "{}",
            initialCustomResponsePath = "$.choices[0].text",
            initialCustomRequestTemplate = "{}",
            initialCustomMessageFormat = "{}",
            initialCustomSystemRole = "system",
            initialCustomUserRole = "user",
            initialCustomAssistantRole = "assistant",
            initialSttMode = SttMode.PARAKEET,
            initialTtsMode = com.app.assistant.tts.TtsMode.OFFLINE,
            isTtsInstalled = true,
            verificationState = VerificationState.Success(
                capabilities = com.app.assistant.llm.ModelCapabilities(
                    hasImageInput = true,
                    hasAudioInput = false,
                    hasVideoInput = false,
                    hasDocumentInput = true
                )
            ),
            fetchedModelsMap = mapOf(LlmProvider.CUSTOM to listOf("my-custom-model", "another-one")),
            isFetchingModels = false,
            modelFetchError = null,
            isModelInstalled = true,
            modelDownloadState = DownloadState.Idle,
            ttsDownloadState = DownloadState.Idle,
            isTranslationEnabled = false,
            activeLanguageCode = "en",
            downloadedTranslationLanguages = emptySet(),
            downloadingTranslationLanguages = emptySet(),
            translationLanguages = emptyList(),
            isDefaultAssistant = true,
            onBack = {},
            onResetVerificationState = {},
            onFetchModelsForProvider = { _, _, _, _ -> },
            onVerifyModelAndSaveCapabilities = { _, _, _, _, _, _, _, _, _, _, _ -> },
            onStartModelDownload = {},
            onDeleteModel = {},
            onCancelModelDownload = {},
            onStartTtsModelDownload = {},
            onDeleteTtsModel = {},
            onCancelTtsModelDownload = {},
            onTranslationEnabledChange = {},
            onLanguageSelected = {},
            onDownloadTranslationModel = {},
            onDeleteTranslationModel = {}
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationLanguageManagerBottomSheet(
    sheetState: SheetState,
    languages: List<Pair<String, String>>,
    activeLanguageCode: String,
    downloadedLanguages: Set<String>,
    downloadingLanguages: Set<String>,
    onLanguageSelected: (String) -> Unit,
    onDownloadLanguage: (String) -> Unit,
    onDeleteLanguage: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredLanguages = remember(searchQuery, languages) {
        languages.filter { it.first.contains(searchQuery, true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = "Управление языками перевода",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                value = searchQuery,
                placeholder = { Text("Поиск языков…") },
                singleLine = true,
                onValueChange = { searchQuery = it },
                shape = RoundedCornerShape(12.dp),
                leadingIcon = {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Info,
                        contentDescription = "Поиск"
                    )
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(filteredLanguages) { lang ->
                    val langCode = lang.second
                    val rawName = lang.first
                    val displayName = remember(rawName) {
                        formatLanguageName(rawName)
                    }
                    val isDefault = langCode == activeLanguageCode
                    val isDownloaded = langCode in downloadedLanguages || langCode == "en"
                    val isDownloading = langCode in downloadingLanguages

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDefault) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f) else Color.Transparent,
                        border = if (isDefault) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                if (isDownloaded) {
                                    onLanguageSelected(langCode)
                                } else if (!isDownloading) {
                                    onDownloadLanguage(langCode)
                                    onLanguageSelected(langCode)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isDefault,
                                onClick = {
                                    if (isDownloaded) {
                                        onLanguageSelected(langCode)
                                    } else if (!isDownloading) {
                                        onDownloadLanguage(langCode)
                                        onLanguageSelected(langCode)
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isDefault) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = "Код: $langCode",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            when {
                                isDownloading -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                                isDownloaded -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        StatusBadge(text = "Установлена", isInstalled = true)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        if (langCode != "en") {
                                            IconButton(onClick = { onDeleteLanguage(langCode) }) {
                                                Icon(
                                                    imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                                                    contentDescription = "Удалить модель",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    IconButton(
                                        onClick = {
                                            onDownloadLanguage(langCode)
                                            onLanguageSelected(langCode)
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_download),
                                            contentDescription = "Скачать модель",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatLanguageName(name: String): String {
    return name.lowercase().split("_").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
}

private fun openAssistantSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (ex: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (exc: Exception) {
                // Ignore
            }
        }
    }
}


