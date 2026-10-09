package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.os.Environment
import com.example.model.LocalRecordingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class RecordingsRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("velostream_recordings_meta", Context.MODE_PRIVATE)

    private val _recordings = MutableStateFlow<List<LocalRecordingItem>>(emptyList())
    val recordings: StateFlow<List<LocalRecordingItem>> = _recordings.asStateFlow()

    fun saveRecordedDuration(filePath: String, durationMs: Long) {
        if (durationMs > 0) {
            val key = File(filePath).name
            prefs.edit().putLong(key, durationMs).apply()
        }
    }

    suspend fun refreshRecordings() = withContext(Dispatchers.IO) {
        val targetDirs = listOfNotNull(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "VeloStream"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            File(context.filesDir, "movies")
        )

        val scannedFiles = mutableListOf<File>()
        val seenPaths = mutableSetOf<String>()

        for (dir in targetDirs) {
            if (dir.exists() && dir.isDirectory) {
                val list = dir.listFiles { file ->
                    file.isFile && file.name.endsWith(".mp4", ignoreCase = true) && file.length() > 0 &&
                            (dir.name == "VeloStream" || file.name.startsWith("VeloStream", ignoreCase = true))
                } ?: emptyArray()

                for (f in list) {
                    val canonical = try { f.canonicalPath } catch (_: Exception) { f.absolutePath }
                    if (seenPaths.add(canonical)) {
                        scannedFiles.add(f)
                    }
                }
            }
        }

        val files = scannedFiles.sortedByDescending { it.lastModified() }

        val items = files.mapIndexed { index, file ->
            var durationMs = prefs.getLong(file.name, 0L)

            // If duration in prefs is missing or suspiciously short (< 1000ms for a file > 50KB), query the MP4 header
            if (durationMs < 1000L) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val parsed = durStr?.toLongOrNull() ?: 0L
                    if (parsed > 0) {
                        durationMs = parsed
                        saveRecordedDuration(file.absolutePath, parsed)
                    }
                    retriever.release()
                } catch (_: Exception) {}
            }

            // Fallback for valid video files where header duration could not be parsed:
            if (durationMs <= 0L && file.length() > 20_000L) {
                // Estimate based on standard 6 Mbps recording bitrate:
                val estimatedMs = (file.length() * 8000L) / 6_000_000L
                durationMs = maxOf(1000L, estimatedMs)
                saveRecordedDuration(file.absolutePath, durationMs)
            }

            LocalRecordingItem(
                id = index.toLong(),
                title = file.name,
                filePath = file.absolutePath,
                durationMs = durationMs,
                sizeBytes = file.length(),
                dateAdded = file.lastModified()
            )
        }
        _recordings.value = items
    }

    suspend fun deleteRecording(filePath: String) = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (file.exists()) {
            file.delete()
        }
        prefs.edit().remove(file.name).apply()
        refreshRecordings()
    }
}
