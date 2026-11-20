package com.example.aura.ui.components

/**
 * ARCHIVO: Dialogs.kt
 * FUNCIÓN: Diálogos reutilizables de la app: crear lista de reproducción, información de la pista
 *          y temporizador de apagado (sleep timer). Incluye la fila de datos InfoRow.
 * NAVEGACIÓN: AuraApp abre estos diálogos; las acciones llegan al ViewModel/AudioPlayerManager.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aura.model.Song
import com.example.ui.theme.AuraAccentPink
import com.example.ui.theme.AuraCardBorder
import com.example.ui.theme.AuraDarkSurface
import com.example.ui.theme.AuraDarkSurfaceVariant
import com.example.ui.theme.AuraPrimaryPurple
import com.example.ui.theme.AuraPrimaryPurpleLight
import com.example.ui.theme.AuraTextSecondary

@Composable
/**
 * CreatePlaylistDialog: pide nombre, descripción y color de portada para una nueva lista.
 */
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, colorHex: Long) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedColor by remember { mutableLongStateOf(0xFF9155FD) }

    val colors = listOf(
        0xFF9155FD to "Morado",
        0xFF5B78EE to "Azul",
        0xFF2EC4B6 to "Turquesa",
        0xFFE85D9E to "Rosa",
        0xFF8B7DE8 to "Lavanda",
        0xFFE65D79 to "Coral"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AuraDarkSurface,
        title = {
            Text(
                text = "Nueva Lista de Reproducción",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre de la lista", color = AuraTextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AuraPrimaryPurple,
                        unfocusedBorderColor = AuraCardBorder
                    )
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción (opcional)", color = AuraTextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AuraPrimaryPurple,
                        unfocusedBorderColor = AuraCardBorder
                    )
                )

                Text(
                    text = "Color de portada",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraTextSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    colors.forEach { (colorVal, colorName) ->
                        val isSelected = selectedColor == colorVal
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(colorVal))
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { selectedColor = colorVal }
                                )
                                .then(
                                    if (isSelected) Modifier.border(2.dp, Color.White, CircleShape)
                                    else Modifier
                                )
                                .semantics {
                                    contentDescription = if (isSelected) "$colorName, seleccionado" else colorName
                                }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim(), description.trim(), selectedColor)
                    }
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AuraPrimaryPurple,
                    contentColor = Color.White
                )
            ) {
                Text("Crear", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = AuraTextSecondary)
            }
        }
    )
}

@Composable
/**
 * SongInfoDialog: ficha técnica de la pista (formato, calidad, sample rate, ruta, etc.).
 */
fun SongInfoDialog(
    song: Song,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AuraDarkSurface,
        title = {
            Text(
                text = "Detalles de la pista",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoRow("Título", song.title)
                InfoRow("Artista", song.artist)
                InfoRow("Álbum", song.album)
                InfoRow("Formato", song.format)
                InfoRow("Calidad", song.qualityBadge)
                InfoRow("Tasa de muestreo", if (song.sampleRate > 0) "${song.sampleRate} Hz" else "Desconocida")
                InfoRow("Profundidad de bits", if (song.bitDepth > 0) "${song.bitDepth} bit" else "Desconocida")
                InfoRow("Duración", song.durationFormatted)
                if (!song.filePath.isNullOrEmpty()) {
                    InfoRow("Ruta", song.filePath!!)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AuraPrimaryPurple)
            ) {
                Text("Cerrar", color = Color.White)
            }
        }
    )
}

@Composable
/**
 * InfoRow: fila etiqueta/valor usada dentro de SongInfoDialog.
 */
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = AuraTextSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
/**
 * SleepTimerDialog: permite elegir 15/30/45/60 min, ver el tiempo restante o desactivar el timer.
 */
fun SleepTimerDialog(
    remainingMs: Long,
    onSetTimer: (minutes: Int) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AuraDarkSurface,
        title = {
            Text(
                text = "Temporizador de apagado",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (remainingMs > 0) {
                    val minutesLeft = remainingMs / 1000 / 60
                    val secondsLeft = (remainingMs / 1000) % 60
                    Text(
                        text = "Tiempo restante: %d:%02d".format(minutesLeft, secondsLeft),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = AuraPrimaryPurpleLight
                    )
                } else {
                    Text(
                        text = "Selecciona el tiempo para detener la reproducción automáticamente:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraTextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        Button(
                            onClick = { onSetTimer(minutes); onDismiss() },
                            colors = ButtonDefaults.buttonColors(containerColor = AuraDarkSurfaceVariant)
                        ) {
                            Text("$minutes min")
                        }
                    }
                }

                if (remainingMs > 0) {
                    TextButton(
                        onClick = { onCancelTimer(); onDismiss() },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Desactivar temporizador", color = AuraAccentPink)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = AuraTextSecondary)
            }
        }
    )
}
