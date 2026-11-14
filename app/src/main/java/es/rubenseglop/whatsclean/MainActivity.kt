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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
    val framesAnalyzed: Int
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
                            Text("Tu biblioteca de vídeos, bajo control", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        androidx.compose.material3.IconButton(
                            onClick = { if (permissionGranted) scan() },
                            enabled = permissionGranted && !isScanning
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Volver a analizar")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Primero, seguridad.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "WhatsClean analiza en este dispositivo. En esta primera versión no borra, mueve ni modifica ningún vídeo.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (!permissionGranted) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Necesitamos permiso para ver tus vídeos")
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryCard("Vídeos encontrados", if (hasScanned) videos.size.toString() else "—", Modifier.weight(1f))
                        SummaryCard("Espacio ocupado", if (hasScanned) formatBytes(videos.sumOf { it.size }) else "—", Modifier.weight(1f))
                    }

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Análisis visual con IA en el dispositivo", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Examina fotogramas de los vídeos seleccionados (o de toda la biblioteca si no seleccionas ninguno). El modelo funciona sin conexión. Detectar personas o mascotas activa una señal de protección; no detectar nada nunca significa que el vídeo sea basura.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isAnalyzing = true
                                            analysisProgress = 0
                                            val targets = if (selectedIds.isEmpty()) videos else videos.filter { it.id in selectedIds }
                                            val labeler = ImageLabeling.getClient(
                                                ImageLabelerOptions.Builder()
                                                    .setConfidenceThreshold(0.55f)
                                                    .build()
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
                                    },
                                    enabled = !isAnalyzing && videos.isNotEmpty()
                                ) {
                                    Text(if (isAnalyzing) "Analizando…" else "Analizar con IA")
                                }
                                if (isAnalyzing) {
                                    Text("$analysisProgress / ${if (selectedIds.isEmpty()) videos.size else selectedIds.size}")
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Biblioteca de vídeos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (selectedIds.isEmpty()) "Selecciona los vídeos que quieras revisar"
                                else "${selectedIds.size} seleccionados",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Todos", style = MaterialTheme.typography.labelMedium)
                            Checkbox(
                                checked = videos.isNotEmpty() && selectedIds.size == videos.size,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) videos.map { it.id }.toSet() else emptySet()
                                },
                                enabled = videos.isNotEmpty() && !isScanning
                            )
                        }
                    }
                    when {
                        isScanning -> Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("Analizando vídeos del dispositivo…")
                        }
                        errorMessage != null -> Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        !hasScanned -> Text("Pulsa analizar para comenzar.")
                        videos.isEmpty() -> Text("No se han encontrado vídeos en las ubicaciones multimedia accesibles.")
                        else -> LazyColumn(
                            modifier = Modifier.weight(1f),
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

                if (permissionGranted && hasScanned && !isScanning) {
                    Text(
                        "En WhatsClean, ante la duda se conserva. La clasificación inteligente y la limpieza llegarán después de validar bien la protección de vídeos personales.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                if (analysis != null) {
                    Text(
                        if (analysis.protectedSignal) "Posibles personas o mascotas: PROTEGER"
                        else "Sin señales personales claras: revisar manualmente",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (analysis.protectedSignal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (analysis.labels.isNotEmpty()) {
                        Text(
                            "Detectado: ${analysis.labels.take(4).joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                } else {
                    Text("Pendiente de análisis", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    } finally {
        retriever.release()
    }
    val personalSignals = setOf(
        "person", "people", "human", "face", "selfie", "crowd",
        "child", "baby", "man", "woman", "boy", "girl",
        "dog", "cat", "pet", "puppy", "kitten", "horse"
    )
    val protected = labels.keys.any { label ->
        personalSignals.any { signal -> label.lowercase(Locale.ROOT).contains(signal) }
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
