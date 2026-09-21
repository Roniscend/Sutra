@file:OptIn(ExperimentalMaterial3Api::class)

package live.sutra.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import live.sutra.field.FieldViewModel
import live.sutra.field.ProsodicSpeaker
import live.sutra.field.SpeechRouting
import live.sutra.link.DeliveryTracker

@Composable
fun FieldScreen(
    state: FieldViewModel.UiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPressTalk: () -> Unit,
    onReleaseTalk: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onLossChange: (Int) -> Unit,
    onSendTestUtterance: () -> Unit,
    onInstallVoice: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Field phone", style = MaterialTheme.typography.titleLarge)
                        Text(
                            state.channel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Change role")
                    }
                },
                actions = { LinkAction(state, onConnect, onDisconnect) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LanguagePicker(state, onLanguageChange)
            LinkMeter(state)
            TalkButton(state, onPressTalk, onReleaseTalk)
            StatusLine(state)
            Notices(state, onInstallVoice)
            LossControl(state.simulatedLossPercent, onLossChange)

            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = true,
            ) {
                items(state.messages.reversed(), key = { "${it.outgoing}-${it.sequence}" }) { message ->
                    MessageRow(message, Modifier.animateItem())
                }
            }

            if (com.androidengineers.agent_quickstart_android.BuildConfig.DEBUG) {
                TextButton(
                    onClick = onSendTestUtterance,
                    enabled = state.connected,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Science, contentDescription = null, Modifier.size(18.dp))
                    Text("  Send test frame", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun LinkAction(
    state: FieldViewModel.UiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    if (state.connected) {
        OutlinedButton(onClick = onDisconnect) { Text("Leave") }
    } else {
        Button(onClick = onConnect, enabled = !state.connecting) {
            Text(if (state.connecting) "Joining…" else "Join link")
        }
    }
}

@Composable
private fun LanguagePicker(state: FieldViewModel.UiState, onLanguageChange: (String) -> Unit) {
    val byLanguage = state.capabilities.associateBy { it.language }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FieldViewModel.LANGUAGES.forEach { (code, name) ->
                val engine = byLanguage[code]?.engine ?: SpeechRouting.Engine.NONE
                FilterChip(
                    selected = state.language == code,
                    onClick = { onLanguageChange(code) },
                    label = { Text(name) },
                    leadingIcon = {
                        Icon(
                            imageVector = engineIcon(engine),
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
        Text(
            when (byLanguage[state.language]?.engine) {
                SpeechRouting.Engine.WHISPER -> "Bundled model on this phone, with prosody"
                SpeechRouting.Engine.PLATFORM -> "This phone's own recogniser — text only, no prosody"
                else -> "Cannot be dictated here; still received and spoken"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun engineIcon(engine: SpeechRouting.Engine): ImageVector = when (engine) {
    SpeechRouting.Engine.WHISPER -> Icons.Outlined.Memory
    SpeechRouting.Engine.PLATFORM -> Icons.Outlined.RecordVoiceOver
    SpeechRouting.Engine.NONE -> Icons.Outlined.VolumeOff
}

@Composable
private fun LinkMeter(state: FieldViewModel.UiState) {
    val link = state.link
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Meter(
                Icons.Outlined.DataUsage,
                "Sutra payload",
                "${link.framesSent} frames · ${link.bytesSent} B out · ${link.bytesReceived} B in",
            )
            Meter(
                Icons.Outlined.Speed,
                "Last utterance",
                if (link.lastFrameBytes > 0)
                    "%d B — %.0f bps of speech".format(link.lastFrameBytes, link.lastFrameBitsPerSecond)
                else "nothing sent yet",
                emphasis = link.lastFrameBytes > 0,
            )
            Meter(
                Icons.Outlined.NetworkCheck,
                "Agora channel, measured",
                "tx ${link.channelTxKbps} kbps · rx ${link.channelRxKbps} kbps",
            )
            Meter(Icons.Outlined.Speed, "A voice call instead", "about 24 000 bps, continuously")
            if (state.lastRecognitionMillis > 0) {
                Meter(
                    Icons.Outlined.Memory,
                    "On device",
                    "recognition ${state.lastRecognitionMillis} ms · prosody ${state.lastAnalysisMillis} ms",
                )
            }
            if (link.droppedBySimulation > 0) {
                Meter(Icons.Outlined.ErrorOutline, "Dropped by simulation", "${link.droppedBySimulation} frames")
            }
        }
    }
}

@Composable
private fun Meter(icon: ImageVector, label: String, value: String, emphasis: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "  $label",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        AnimatedContent(
            targetState = value,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "meter",
        ) { shown ->
            Text(
                shown,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TalkButton(
    state: FieldViewModel.UiState,
    onPressTalk: () -> Unit,
    onReleaseTalk: () -> Unit,
) {
    val enabled = state.connected && !state.busy
    val container by animateColorAsState(
        targetValue = when {
            !state.connected -> MaterialTheme.colorScheme.surfaceContainerHighest
            state.listening -> MaterialTheme.colorScheme.error
            state.busy -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(220),
        label = "talkColor",
    )
    val diameter by animateDpAsState(
        targetValue = if (state.listening) 148.dp else 124.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "talkSize",
    )
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (state.listening) 1.18f else 1.0001f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "ring",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(172.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state.listening) {
            Box(
                Modifier
                    .size(diameter)
                    .scale(ring)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f))
            )
        }
        Box(
            Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(container)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            onPressTalk()
                            tryAwaitRelease()
                            onReleaseTalk()
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Hold to talk",
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    when {
                        !state.connected -> "join first"
                        state.busy -> "thinking"
                        state.listening -> "listening"
                        else -> "hold to talk"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(state: FieldViewModel.UiState) {
    AnimatedContent(
        targetState = state.status,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        label = "status",
    ) { status ->
        Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Notices(state: FieldViewModel.UiState, onInstallVoice: () -> Unit) {
    AnimatedVisibility(
        visible = state.error != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Notice(Icons.Outlined.ErrorOutline, state.error.orEmpty(), MaterialTheme.colorScheme.errorContainer)
    }
    AnimatedVisibility(
        visible = !state.modelReady,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Notice(
            Icons.Outlined.Memory,
            "The bundled speech model is unavailable on this device (arm64 only).",
            MaterialTheme.colorScheme.errorContainer,
        )
    }
    VoiceNotice(state.voiceStatus, onInstallVoice)
}

@Composable
private fun VoiceNotice(status: ProsodicSpeaker.VoiceStatus, onInstallVoice: () -> Unit) {
    val (message, offerInstall) = when (status) {
        ProsodicSpeaker.VoiceStatus.AVAILABLE -> return
        ProsodicSpeaker.VoiceStatus.MISSING_DATA ->
            "No voice installed for this language, so messages will not be read out." to true
        ProsodicSpeaker.VoiceStatus.NOT_SUPPORTED ->
            "This device's speech engine has no voice for this language. Messages still arrive as text." to false
        ProsodicSpeaker.VoiceStatus.NO_ENGINE ->
            "No speech engine available yet. Messages still arrive as text." to true
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VolumeOff, contentDescription = null, Modifier.size(18.dp))
                Text("  $message", style = MaterialTheme.typography.bodySmall)
            }
            if (offerInstall) {
                TextButton(onClick = onInstallVoice) { Text("Install voice data") }
            }
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, message: String, container: Color) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
            Text("  $message", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LossControl(percent: Int, onLossChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.NetworkCheck,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "  Simulated packet loss: $percent%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onLossChange(it.toInt()) },
            valueRange = 0f..50f,
            steps = 9,
        )
    }
}

@Composable
private fun MessageRow(message: FieldViewModel.Message, modifier: Modifier = Modifier) {
    val container = when {
        message.urgent -> MaterialTheme.colorScheme.errorContainer
        message.outgoing -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.urgent) {
                    Icon(
                        Icons.Outlined.NotificationsActive,
                        contentDescription = "urgent",
                        modifier = Modifier.size(16.dp),
                    )
                    Text("  ")
                }
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    buildString {
                        append(if (message.outgoing) "sent" else "received")
                        append(" · ${message.bytes} B")
                        if (message.bitsPerSecond > 0) append(" · %.0f bps".format(message.bitsPerSecond))
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                )
                message.delivery?.let { DeliveryMark(it) }
            }
        }
    }
}

@Composable
private fun DeliveryMark(state: DeliveryTracker.State) {
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
        label = "delivery",
    ) { current ->
        val (icon, label, tint) = when (current) {
            DeliveryTracker.State.PENDING ->
                Triple(Icons.Outlined.Schedule, "waiting", MaterialTheme.colorScheme.onSurfaceVariant)
            DeliveryTracker.State.DELIVERED ->
                Triple(Icons.Outlined.CheckCircle, "delivered", MaterialTheme.colorScheme.primary)
            DeliveryTracker.State.LOST ->
                Triple(Icons.Outlined.ErrorOutline, "not acknowledged", MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(14.dp), tint = tint)
            Text("  $label", style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}
