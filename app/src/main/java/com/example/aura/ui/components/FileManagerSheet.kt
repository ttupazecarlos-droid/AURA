package com.example.aura.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aura.data.storage.FileItem
import com.example.aura.data.storage.takePersistableReadPermission
import com.example.ui.theme.AuraAccentTeal
import com.example.ui.theme.AuraCardBorder
import com.example.ui.theme.AuraDarkBackground
import com.example.ui.theme.AuraDarkSurface
import com.example.ui.theme.AuraDarkSurfaceVariant
import com.example.ui.theme.AuraPrimaryPurple
import com.example.ui.theme.AuraSecondaryBlue
import com.example.ui.theme.AuraTextMuted
import com.example.ui.theme.AuraTextSecondary
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerSheet(
    sheetState: SheetState,
    isScanning: Boolean,
    statusMessage: String?,
    currentPath: String?,
    files: List<FileItem>,
    onDismiss: () -> Unit,
    onScanMediaStore: () -> Unit,
    onImportUri: (Uri) -> Unit,
    onImportFolder: (Uri) -> Unit,
    onNavigateDir: (String?) -> Unit,
    onPlayFileItem: (FileItem) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    // Launcher for selecting audio files
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            context.takePersistableReadPermission(uri)
            onImportUri(uri)
        }
    }

    // Launcher for selecting an entire music folder
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            context.takePersistableReadPermission(treeUri)
            onImportFolder(treeUri)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AuraDarkSurface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(AuraPrimaryPurple),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Gestor",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Gestor de Archivos de Música",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.semantics { heading() }
                        )
                        Text(
                            text = "Soporte FLAC, WAV, ALAC, AIFF y MP3",
                            style = MaterialTheme.typography.bodySmall,
                            color = AuraTextSecondary
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar",
                        tint = AuraTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status message or scanning banner
            if (isScanning || statusMessage != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = AuraDarkSurfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = AuraPrimaryPurple,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = statusMessage ?: "Procesando archivos...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Tabs: "Acceso Rápido" & "Explorador de Carpetas"
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = AuraPrimaryPurple,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = AuraPrimaryPurple
                    )
                },
                divider = { HorizontalDivider(color = AuraCardBorder) }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Escanear & Seleccionar", fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Explorar Carpetas", fontWeight = FontWeight.SemiBold) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                0 -> {
                    // Quick Import Actions
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Scan All Storage Button
                        ActionCard(
                            title = "Escanear Todo el Celular",
                            subtitle = "Busca pistas de audio en MediaStore y almacenamiento interno",
                            icon = Icons.Default.Refresh,
                            iconColor = AuraPrimaryPurple,
                            onClick = onScanMediaStore
                        )

                        // Pick Files with SAF
                        ActionCard(
                            title = "Seleccionar Archivos de Música",
                            subtitle = "Abre selector para elegir archivos FLAC, WAV o MP3",
                            icon = Icons.Default.UploadFile,
                            iconColor = AuraAccentTeal,
                            onClick = { filePicker.launch(arrayOf("audio/*")) }
                        )

                        // Pick Folder with SAF
                        ActionCard(
                            title = "Seleccionar Carpeta Completa",
                            subtitle = "Importa una carpeta con álbumes o archivos sin pérdida",
                            icon = Icons.Default.CreateNewFolder,
                            iconColor = AuraSecondaryBlue,
                            onClick = { folderPicker.launch(null) }
                        )
                    }
                }
                1 -> {
                    // Integrated Directory Browser
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Directory breadcrumb
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AuraDarkSurfaceVariant, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (currentPath != null && currentPath != "/") {
                                IconButton(
                                    onClick = {
                                        val parent = File(currentPath).parent
                                        onNavigateDir(parent)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Subir de carpeta",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                text = currentPath ?: "Carpeta Música",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = AuraTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (files.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No se encontraron carpetas o archivos de audio aquí",
                                    color = AuraTextMuted,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(files) { item ->
                                    FileItemRow(
                                        item = item,
                                        onClick = {
                                            if (item.isDirectory) {
                                                onNavigateDir(item.absolutePath)
                                            } else if (item.isAudio) {
                                                onPlayFileItem(item)
                                            }
                                        }
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

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        color = AuraDarkSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, AuraCardBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraTextSecondary
                )
            }
        }
    }
}

@Composable
private fun FileItemRow(
    item: FileItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (item.isDirectory || item.isAudio) Modifier.clickable(
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClickLabel = if (item.isDirectory) "Abrir carpeta" else "Reproducir archivo"
                ) { onClick() }
                else Modifier
            ),
        color = AuraDarkSurfaceVariant.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    item.isDirectory -> Icons.Default.Folder
                    item.isAudio -> Icons.Default.Audiotrack
                    else -> Icons.Default.MusicNote
                },
                contentDescription = null,
                tint = when {
                    item.isDirectory -> AuraSecondaryBlue
                    item.isLossless -> AuraPrimaryPurple
                    else -> AuraAccentTeal
                },
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.isAudio) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.isLossless) {
                            Text(
                                text = "LOSSLESS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = AuraPrimaryPurple
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = formatFileSize(item.sizeBytes),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = AuraTextMuted
                        )
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        "%.1f MB".format(mb)
    } else {
        "%.0f KB".format(kb)
    }
}
