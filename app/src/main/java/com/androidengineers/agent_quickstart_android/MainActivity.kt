package com.androidengineers.agent_quickstart_android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidengineers.agent_quickstart_android.config.QuickstartConfig
import com.androidengineers.agent_quickstart_android.ui.ConversationScreen
import com.androidengineers.agent_quickstart_android.ui.ConversationViewModel
import com.androidengineers.agent_quickstart_android.ui.theme.AgentquickstartandroidTheme
import live.sutra.field.FieldViewModel
import live.sutra.ui.FieldScreen
import live.sutra.ui.RoleScreen
import live.sutra.ui.SutraRole
import live.sutra.ui.SutraControlPanel

class MainActivity : ComponentActivity() {
    private val conversationViewModel by viewModels<ConversationViewModel>()
    private val fieldViewModel by viewModels<FieldViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by conversationViewModel.uiState.collectAsStateWithLifecycle()
            val systemDarkTheme = isSystemInDarkTheme()

            LaunchedEffect(systemDarkTheme) {
                conversationViewModel.initializeTheme(systemDarkTheme)
            }

            AgentquickstartandroidTheme(darkTheme = uiState.isDarkTheme) {
                Surface(Modifier.fillMaxSize()) {
                    var role by rememberSaveable { mutableStateOf<SutraRole?>(null) }
                    var channel by rememberSaveable { mutableStateOf(FieldViewModel.DEFAULT_CHANNEL) }

                    when (role) {
                        null -> RoleScreen(
                            channel = channel,
                            onChannelChange = { channel = it },
                            configMessage = QuickstartConfig.startupHelpMessage(),
                            onRoleChosen = { chosen ->
                                fieldViewModel.setChannel(channel)
                                conversationViewModel.updateChannelName(channel)
                                role = chosen
                            },
                        )

                        SutraRole.FIELD -> FieldRoute(onBack = { role = null })
                        SutraRole.CONTROL -> ControlRoute(onBack = { role = null })
                    }
                }
            }
        }
    }

    @Composable
    private fun FieldRoute(onBack: () -> Unit) {
        val state by fieldViewModel.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val currentViewModel by rememberUpdatedState(fieldViewModel)
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted -> if (granted) currentViewModel.connect() }

        FieldScreen(
            state = state,
            onConnect = {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) currentViewModel.connect()
                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDisconnect = fieldViewModel::disconnect,
            onPressTalk = fieldViewModel::startTalking,
            onReleaseTalk = fieldViewModel::stopTalking,
            onLanguageChange = fieldViewModel::setLanguage,
            onLossChange = fieldViewModel::setSimulatedLoss,
            onSendTestUtterance = fieldViewModel::sendTestUtterance,
            onInstallVoice = {

                runCatching {
                    context.startActivity(
                        android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onBack = {
                fieldViewModel.disconnect()
                onBack()
            },
        )
    }

    @Composable
    private fun ControlRoute(onBack: () -> Unit) {
        val uiState by conversationViewModel.uiState.collectAsStateWithLifecycle()
        val sutraState by conversationViewModel.sutraState.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val currentViewModel by rememberUpdatedState(conversationViewModel)
        var panelCollapsed by rememberSaveable { mutableStateOf(false) }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            currentViewModel.updateMicrophonePermission(granted)
            if (granted) currentViewModel.startConversation()
        }

        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            currentViewModel.updateMicrophonePermission(granted)
        }

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SutraControlPanel(
                state = sutraState,
                collapsed = panelCollapsed,
                onToggle = { panelCollapsed = !panelCollapsed },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Box(Modifier.weight(1f)) {
                ConversationScreen(
                    uiState = uiState,
                    onStartRequested = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        currentViewModel.updateMicrophonePermission(granted)
                        if (granted) currentViewModel.startConversation()
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onEndConversation = {
                        conversationViewModel.endConversation()
                        onBack()
                    },
                    onToggleMicrophone = conversationViewModel::toggleMicrophone,
                    onToggleTheme = conversationViewModel::toggleTheme,
                    onDismissMessages = conversationViewModel::clearTransientMessages,
                    onTextChanged = conversationViewModel::updateTextDraft,
                    onSendText = conversationViewModel::sendText,
                )
            }
        }
    }
}
