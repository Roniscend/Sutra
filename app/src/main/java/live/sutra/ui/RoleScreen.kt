package live.sutra.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HeadsetMic
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

enum class SutraRole { FIELD, CONTROL }

@Composable
fun RoleScreen(
    channel: String,
    onChannelChange: (String) -> Unit,
    configMessage: String?,
    onRoleChosen: (SutraRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.CellTower,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("  Sutra", style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            "Voice over a thread. Speech travels as meaning, not audio — about a hundred bytes an utterance — so a conversation survives a link that cannot carry a call.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = channel,
            onValueChange = onChannelChange,
            label = { Text("Channel — both phones must match") },
            leadingIcon = { Icon(Icons.Outlined.CellTower, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 8 },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RoleCard(
                    icon = Icons.Outlined.Hiking,
                    title = "Field phone",
                    body = "For the weak link. No audio is published or subscribed: speech is recognised on the device and sent as frames. The bundled model needs an arm64 phone.",
                    primary = true,
                    action = "Join as field phone",
                    onClick = { onRoleChosen(SutraRole.FIELD) },
                )
                RoleCard(
                    icon = Icons.Outlined.HeadsetMic,
                    title = "Control room",
                    body = "For the good network. Runs the Agora Conversational AI agent: it reads field reports aloud, answers questions about them, and relays your spoken instructions back down the thin link.",
                    primary = false,
                    action = "Open control room",
                    onClick = { onRoleChosen(SutraRole.CONTROL) },
                )
            }
        }

        AnimatedVisibility(visible = configMessage != null, enter = fadeIn()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, Modifier.size(18.dp))
                    Text(
                        "  ${configMessage.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleCard(
    icon: ImageVector,
    title: String,
    body: String,
    primary: Boolean,
    action: String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (primary) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, Modifier.size(24.dp))
                Text("  $title", style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodySmall)
            if (primary) {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(action) }
            } else {
                OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(action) }
            }
        }
    }
}
