package es.rubenseglop.whatsclean

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.withContext
import java.util.Locale

data class VideoEntry(
    val id: Long,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val dateAddedSeconds: Long,
    val uri: Uri
)

data class VisualAnalysis(
    val protectedSignal: Boolean,
    val labels: List<String>,
    val framesAnalyzed: Int,
    val errorMessage: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WhatsCleanScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun WhatsCleanScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var videos by remember { mutableStateOf<List<VideoEntry>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var analyses by remember { mutableStateOf<Map<Long, VisualAnalysis>>(emptyMap()) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analysisProgress by remember { mutableStateOf(0) }
    var hasScanned by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(hasVideoPermission(context)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currentTab by remember { mutableStateOf(0) }

    val protectedVideos = videos.filter { analyses[it.id]?.protectedSignal == true }
    val reviewVideos = videos.filter {
        val result = analyses[it.id]
        result != null && result.errorMessage == null && result.framesAnalyzed > 0 && !result.protectedSignal
    }
    val unanalyzedCount = videos.count { analyses[it.id] == null }

    fun scan() {
        scope.launch {
            isScanning = true
            errorMessage = null
            try {
                videos = withContext(Dispatchers.IO) { queryVideos(context) }
                selectedIds = emptySet()
                analyses = emptyMap()
                hasScanned = true
            } catch (error: Exception) {
                errorMessage = error.localizedMessage ?: "No se ha podido leer la biblioteca multimedia."
            } finally {
                isScanning = false
            }
        }
    }

    fun runAiAnalysis() {
        if (isAnalyzing || videos.isEmpty()) return
        scope.launch {
            isAnalyzing = true
            analysisProgress = 0
            errorMessage = null
            val targets = if (selectedIds.isEmpty()) videos else videos.filter { it.id in selectedIds }
            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
            )
            try {
                val resultMap = analyses.toMutableMap()
                for (video in targets) {
                    resultMap[video.id] = withContext(Dispatchers.IO) {
                        analyzeVideo(context, video, labeler)
                    }
                    analyses = resultMap.toMap()
                    analysisProgress++
                }
            } catch (error: Exception) {
                errorMessage = "No se pudo completar el análisis: ${error.localizedMessage ?: "error desconocido"}"
            } finally {
                labeler.close()
                isAnalyzing = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionGranted = results.values.any { it } || hasVideoPermission(context)
        if (permissionGranted) scan()
    }

    LaunchedEffect(Unit) {
        if (permissionGranted && !hasScanned) scan()
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("WhatsClean", fontWeight = FontWeight.Bold)
                            Text(
                                when (currentTab) {
                                    0 -> "Tu espacio, bajo control"
                                    1 -> "Inventario y análisis visual"
                                    2 -> "Revisión segura"
                                    else -> "Privacidad y preferencias"
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    actions = {
                        androidx.compose.material3.IconButton(
                            onClick = { if (permissionGranted) scan() },
                            enabled = permissionGranted && !isScanning && !isAnalyzing
                        ) { Icon(Icons.Default.Refresh, contentDescription = "Actualizar biblioteca") }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTab == 0, onClick = { currentTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text("Inicio") }
                    )
                    NavigationBarItem(
                        selected = currentTab == 1, onClick = { currentTab = 1 },
                        icon = { Icon(Icons.Default.Analytics, contentDescription = null) }, label = { Text("Análisis") }
                    )
                    NavigationBarItem(
                        selected = currentTab == 2, onClick = { currentTab = 2 },
                        icon = { Icon(Icons.Default.CleaningServices, contentDescription = null) }, label = { Text("Limpieza") }
                    )
                    NavigationBarItem(
                        selected = currentTab == 3, onClick = { currentTab = 3 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) }, label = { Text("Ajustes") }
                    )
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!permissionGranted) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Necesitamos permiso para ver tus vídeos", fontWeight = FontWeight.SemiBold)
                            Text("Solo se utiliza para crear el inventario local. No se envían archivos a Internet.")
                            Button(onClick = {
                                val permissions = when {
                                    Build.VERSION.SDK_INT >= 34 -> arrayOf(
                                        Manifest.permission.READ_MEDIA_VIDEO,
                                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                                    )
                                    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                                    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                                permissionLauncher.launch(permissions)
                            }) { Text("Dar permiso y analizar") }
                        }
                    }
                } else {
                    when (currentTab) {
                        0 -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Primero, seguridad.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("La IA funciona en este dispositivo. WhatsClean todavía no borra ni modifica archivos.")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                SummaryCard("Vídeos", if (hasScanned) videos.size.toString() else "—", Modifier.weight(1f))
                                SummaryCard("Espacio", if (hasScanned) formatBytes(videos.sumOf { it.size }) else "—", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                SummaryCard("Personales", protectedVideos.size.toString(), Modifier.weight(1f))
                                SummaryCard("Por revisar", reviewVideos.size.toString(), Modifier.weight(1f))
                            }
                            if (isScanning) {
                                CircularProgressIndicator()
                                Text("Leyendo vídeos del dispositivo…")
                            } else {
                                Button(onClick = { currentTab = 1 }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Abrir análisis de vídeos")
                                }
                                OutlinedButton(onClick = { currentTab = 2 }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Ver vídeos para revisar")
                                }
                            }
                        }
                        1 -> {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Biblioteca", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("${videos.size} vídeos · ${selectedIds.size} seleccionados", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = {
                                    selectedIds = if (selectedIds.size == videos.size) emptySet() else videos.map { it.id }.toSet()
                                }, enabled = videos.isNotEmpty()) {
                                    Text(if (selectedIds.size == videos.size && videos.isNotEmpty()) "Quitar todos" else "Seleccionar todos")
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { runAiAnalysis() }, enabled = !isAnalyzing && videos.isNotEmpty()) {
                                    Text(if (isAnalyzing) "Analizando…" else "Analizar con IA")
                                }
                                if (isAnalyzing) {
                                    Text("$analysisProgress / ${if (selectedIds.isEmpty()) videos.size else selectedIds.size}")
                                }
                            }
                            if (errorMessage != null) {
                                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            when {
                                isScanning -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                                videos.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                    Text("No se han encontrado vídeos.")
                                }
                                else -> LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(videos, key = { it.id }) { video ->
                                        VideoRow(
                                            video = video,
                                            selected = video.id in selectedIds,
                                            onSelectedChange = { checked ->
                                                selectedIds = if (checked) selectedIds + video.id else selectedIds - video.id
                                            },
                                            analysis = analyses[video.id]
                                        )
                                    }
                                }
                            }
                        }
                        2 -> {
                            Text("Revisión de la IA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "La IA no decide qué borrar. Separa señales personales de vídeos que conviene revisar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                SummaryCard("Proteger", protectedVideos.size.toString(), Modifier.weight(1f))
                                SummaryCard("Revisar", reviewVideos.size.toString(), Modifier.weight(1f))
                            }
                            if (reviewVideos.isNotEmpty()) {
                                Button(
                                    onClick = { selectedIds = reviewVideos.map { it.id }.toSet(); currentTab = 1 },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Seleccionar vídeos para revisar") }
                            } else if (!hasScanned || unanalyzedCount > 0) {
                                Text("Aún no hay resultados. Ve a Análisis y ejecuta la IA.")
                                OutlinedButton(onClick = { currentTab = 1 }) { Text("Ir a Análisis") }
                            } else {
                                Text("No hay vídeos pendientes de revisión.")
                            }
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (protectedVideos.isNotEmpty()) {
                                    item { Text("Vídeos personales · conservar", fontWeight = FontWeight.SemiBold) }
                                    items(protectedVideos, key = { it.id }) { video ->
                                        VideoRow(video, video.id in selectedIds, { checked ->
                                            selectedIds = if (checked) selectedIds + video.id else selectedIds - video.id
                                        }, analyses[video.id])
                                    }
                                }
                                if (reviewVideos.isNotEmpty()) {
                                    item { Text("Contenido dudoso · revisar", fontWeight = FontWeight.SemiBold) }
                                    items(reviewVideos, key = { it.id }) { video ->
                                        VideoRow(video, video.id in selectedIds, { checked ->
                                            selectedIds = if (checked) selectedIds + video.id else selectedIds - video.id
                                        }, analyses[video.id])
                                    }
                                }
                            }
                        }
                        else -> {
                            Text("Privacidad", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Procesamiento local", fontWeight = FontWeight.SemiBold)
                                    Text("Los fotogramas se analizan en el dispositivo. No se suben vídeos ni imágenes a servidores.")
                                }
                            }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Regla de seguridad", fontWeight = FontWeight.SemiBold)
                                    Text("Un vídeo personal se conserva. Si el análisis falla o no es concluyente, también se conserva.")
                                    Text("Esta versión no borra, mueve ni modifica ningún archivo.")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@androidx.compose.runtime.Composable
private fun VideoRow(
    video: VideoEntry,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    analysis: VisualAnalysis?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VideoThumbnail(video)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(video.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2)
                Text(
                    "${formatBytes(video.size)} · ${formatDuration(video.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val status = when {
                    analysis == null -> "Pendiente"
                    analysis.errorMessage != null || analysis.framesAnalyzed == 0 -> "No analizado · conservar"
                    analysis.protectedSignal -> "Personal · conservar"
                    else -> "Contenido dudoso · revisar"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        analysis?.protectedSignal == true -> MaterialTheme.colorScheme.primary
                        analysis?.errorMessage != null -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (analysis?.labels?.isNotEmpty() == true) {
                    Text(
                        analysis.labels.take(3).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Checkbox(checked = selected, onCheckedChange = onSelectedChange)
        }
    }
}

@androidx.compose.runtime.Composable
private fun VideoThumbnail(video: VideoEntry) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = video.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(video.uri, Size(320, 220), null)
            } catch (_: Exception) {
                null
            }
        }
    }
    Surface(
        modifier = Modifier.size(width = 104.dp, height = 72.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Vista previa de ${video.name}",
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Default.Movie,
                contentDescription = "Miniatura no disponible",
                modifier = Modifier.padding(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

private fun analyzeVideo(
    context: android.content.Context,
    video: VideoEntry,
    labeler: ImageLabeler
): VisualAnalysis {
    val retriever = MediaMetadataRetriever()
    val labels = linkedMapOf<String, Float>()
    var framesAnalyzed = 0
    var extractionError: String? = null
    try {
        retriever.setDataSource(context, video.uri)
        val durationUs = video.durationMs.coerceAtLeast(1) * 1000L
        val samplePoints = listOf(0.15, 0.5, 0.85)
        for (point in samplePoints) {
            val bitmap = retriever.getFrameAtTime(
                (durationUs * point).toLong(),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: continue
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val frameLabels = Tasks.await(labeler.process(image))
                framesAnalyzed++
                frameLabels.forEach { item ->
                    val old = labels[item.text] ?: 0f
                    if (item.confidence > old) labels[item.text] = item.confidence
                }
            } finally {
                bitmap.recycle()
            }
        }
        if (framesAnalyzed == 0) {
            extractionError = "Android no pudo extraer fotogramas de este archivo."
        }
    } catch (error: Exception) {
        extractionError = error.localizedMessage ?: error.javaClass.simpleName
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
            // La liberación no debe detener el análisis de otros vídeos.
        }
    }
    if (extractionError != null) {
        return VisualAnalysis(
            protectedSignal = false,
            labels = emptyList(),
            framesAnalyzed = framesAnalyzed,
            errorMessage = extractionError
        )
    }
    val personalSignals = setOf(
        "person", "people", "human", "face", "selfie", "crowd",
        "child", "baby", "man", "woman", "boy", "girl",
        "dog", "cat", "pet", "puppy", "kitten", "horse"
    )
    val protected = labels.keys.any { label ->
        label.lowercase(Locale.ROOT) in personalSignals
    }
    return VisualAnalysis(
        protectedSignal = protected,
        labels = labels.entries.sortedByDescending { it.value }.take(6).map { it.key },
        framesAnalyzed = framesAnalyzed
    )
}

private fun hasVideoPermission(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT >= 34) {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_VIDEO
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
    }
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun queryVideos(context: android.content.Context): List<VideoEntry> {
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_ADDED
    )
    val result = mutableListOf<VideoEntry>()
    context.contentResolver.query(
        collection,
        projection,
        null,
        null,
        "${MediaStore.Video.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            result += VideoEntry(
                id = id,
                name = cursor.getString(nameColumn) ?: "Vídeo sin nombre",
                size = cursor.getLong(sizeColumn).coerceAtLeast(0),
                durationMs = cursor.getLong(durationColumn).coerceAtLeast(0),
                dateAddedSeconds = cursor.getLong(dateColumn),
                uri = ContentUris.withAppendedId(collection, id)
            )
        }
    }
    return result
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024 && unit < units.lastIndex)
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
