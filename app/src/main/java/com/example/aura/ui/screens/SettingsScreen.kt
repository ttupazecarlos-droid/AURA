package com.example.aura.ui.screens

/**
 * ARCHIVO: SettingsScreen.kt
 * FUNCIÓN: Pantalla de ajustes: cuenta, notificaciones, sonido Hi-Fi, descargas/gestor de archivos
 *          y diálogo de ayuda con los formatos soportados.
 * NAVEGACIÓN: AuraApp (AuraScreen.SETTINGS) -> SettingsScreen -> ViewModel / gestor de archivos.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AuraCardBorder
import com.example.ui.theme.AuraDarkBackground
import com.example.ui.theme.AuraDarkSurfaceVariant
import com.example.ui.theme.AuraPrimaryPurple
import com.example.ui.theme.AuraPrimaryPurpleLight
import com.example.ui.theme.AuraScreenTitle
import com.example.ui.theme.AuraTextSecondary

@Composable
/**
 * SettingsScreen: lista de ajustes con switches y acceso al gestor de archivos.
 */
fun SettingsScreen(
    notificationsEnabled: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    onOpenFileExplorer: () -> Unit
) {
    var showHelpDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AuraDarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .testTag("settings_screen")
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "AJUSTES",
            style = AuraScreenTitle,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .semantics { heading() }
        )

        Spacer(modifier = Modifier.height(30.dp))

        SettingItemRow(
            title = "Notificaciones",
            subtitle = "Controles en pantalla de bloqueo",
            icon = Icons.Default.Notifications,
            checked = notificationsEnabled,
            onCheckedChange = onToggleNotifications
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Informativo: no hay un interruptor porque el modo sin pérdida no se puede desactivar.
        SettingItemRow(
            title = "Sonido",
            subtitle = "Formatos sin pérdida según la compatibilidad del dispositivo",
            icon = Icons.Default.Settings
        )

        Spacer(modifier = Modifier.height(18.dp))

        SettingItemRow(
            title = "Carpetas de música",
            subtitle = "Elige las rutas que AURA puede leer y buscar archivos nuevos",
            icon = Icons.Default.Download,
            onItemClick = onOpenFileExplorer
        )

        Spacer(modifier = Modifier.height(18.dp))

        SettingItemRow(
            title = "Ayuda",
            subtitle = "Formatos soportados, FLAC y soporte",
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            onItemClick = { showHelpDialog = true }
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (showHelpDialog) {
            AlertDialog(
                onDismissRequest = { showHelpDialog = false },
                containerColor = AuraDarkSurfaceVariant,
                title = {
                    Text(
                        text = "Acerca de AURA",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "AURA es un reproductor de música de alta fidelidad diseñado para reproducir audio local y archivos sin pérdida.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Text(
                            text = "• Formatos locales: FLAC, WAV, ALAC, AIFF, MP3, AAC y OGG, según los decodificadores del teléfono. MP3 y AAC son formatos con pérdidas.\n• Los datos de calidad describen el archivo, no garantizan la salida efectiva del dispositivo.\n• AURA solo lee las carpetas que autorizas. Las rutas se conservan al actualizar la app.\n• La biblioteca excluye audios de menos de 60 segundos, notas de voz y tonos.\n• Las letras .lrc se buscan junto al audio en carpetas autorizadas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AuraTextSecondary
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showHelpDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = AuraPrimaryPurple)
                    ) {
                        Text("Entendido", color = Color.White)
                    }
                }
            )
        }
    }
}

@Composable
/**
 * SettingItemRow: fila reutilizable de ajuste (icono + título + subtítulo + switch opcional).
 */
private fun SettingItemRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onItemClick: (() -> Unit)? = null
) {
    val hasSwitch = onCheckedChange != null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(
                when {
                    hasSwitch -> Modifier.toggleable(
                        value = checked,
                        role = Role.Switch,
                        onValueChange = onCheckedChange!!
                    )
                    onItemClick != null -> Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = title,
                        onClick = onItemClick
                    )
                    else -> Modifier
                }
            ),
        color = AuraDarkSurfaceVariant.copy(alpha = 0.7f),
        border = androidx.compose.foundation.BorderStroke(1.dp, AuraCardBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AuraPrimaryPurpleLight,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraTextSecondary
                )
            }

            if (hasSwitch) {
                // El estado y la etiqueta los aporta la fila toggleable; el switch no duplica el foco.
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AuraPrimaryPurple,
                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                        uncheckedTrackColor = AuraCardBorder
                    )
                )
            }
        }
    }
}
