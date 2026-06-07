package com.moyue.app.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * TTS Recording Module — loosely coupled, independent of playback code.
 *
 * Records TTS audio for chapters or entire books using the reader's
 * currently selected TTS provider (Edge TTS, AI Voice, Custom TTS).
 *
 * Architecture:
 * - New module at app/src/main/java/com/moyue/tts/TtsRecorder.kt
 * - Reuses each provider's existing `fetchAudio(text)` method
 * - Concatenates MP3 segments into a single file
 * - Saves to context.filesDir/recordings/{bookId}/
 *
 * MP3 concatenation strategy:
 * - Keep ID3v2 header from the FIRST segment only
 * - Strip ID3v2 from all subsequent segments
 * - Append raw audio frames (MP3 frame sync is self-synchronizing)
 *
 * System TTS is NOT supported for recording (no direct audio byte export).
 * Users should switch to Edge TTS / AI Voice / Custom TTS for recording.
 */
object TtsRecorder {

    private const val TAG = "TtsRecorder"

    /** Recording progress callback */
    data class Progress(
        val currentSegment: Int,
        val totalSegments: Int,
        val currentText: String = "",
        val bytesWritten: Long = 0L,
    ) {
        val percentage: Int get() = if (totalSegments > 0) (currentSegment * 100) / totalSegments else 0
    }

    /** Recording result */
    sealed class Result {
        data class Success(val file: File, val totalSegments: Int, val totalBytes: Long) : Result()
        data class Cancelled(val file: File?, val segmentsCompleted: Int) : Result()
        data class Error(val message: String, val cause: Throwable? = null) : Result()
    }

    /**
     * Record TTS audio for a list of text segments.
     *
     * @param fetchAudio Suspend function that fetches MP3 bytes for a text segment
     * @param segments List of text paragraphs to record
     * @param outputFile Destination MP3 file
     * @param onProgress Callback invoked after each segment
     * @return Result (Success / Cancelled / Error)
     */
    suspend fun record(
        fetchAudio: suspend (text: String) -> ByteArray?,
        segments: List<String>,
        outputFile: File,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {

        val validSegments = segments.filter { it.trim().length >= 2 }
        if (validSegments.isEmpty()) {
            return@withContext Result.Error("No valid text segments (each segment must be ≥ 2 chars)")
        }

        Log.d(TAG, "Recording started: ${validSegments.size} segments → ${outputFile.name}")
        outputFile.parentFile?.mkdirs()

        var isFirst = true
        var totalBytes = 0L

        try {
            for ((index, segment) in validSegments.withIndex()) {
                if (!isActive) {
                    return@withContext Result.Cancelled(
                        file = outputFile.takeIf { it.exists() && it.length() > 0 },
                        segmentsCompleted = index,
                    )
                }

                onProgress(Progress(
                    currentSegment = index,
                    totalSegments = validSegments.size,
                    currentText = segment.take(30),
                    bytesWritten = totalBytes,
                ))

                Log.d(TAG, "  Segment ${index + 1}/${validSegments.size}: '${segment.take(30)}...'")

                val audioBytes = try {
                    fetchAudio(segment)
                } catch (e: Exception) {
                    Log.w(TAG, "fetchAudio failed for segment ${index + 1}: ${e.message}")
                    null
                }

                if (audioBytes == null || audioBytes.isEmpty()) {
                    Log.w(TAG, "Empty audio for segment ${index + 1}, skipping")
                    continue
                }

                // MP3 concatenation: strip ID3v2 header from all but first segment
                val bytesToWrite = if (isFirst) {
                    isFirst = false
                    audioBytes
                } else {
                    stripId3v2Header(audioBytes)
                }

                FileOutputStream(outputFile, true /* append */).use { fos ->
                    fos.write(bytesToWrite)
                    totalBytes += bytesToWrite.size
                }

                Log.d(TAG, "  → Wrote ${bytesToWrite.size} bytes (total: ${totalBytes / 1024} KB)")
            }

            // Final progress
            onProgress(Progress(
                currentSegment = validSegments.size,
                totalSegments = validSegments.size,
                bytesWritten = totalBytes,
            ))

            Log.d(TAG, "Recording complete: ${validSegments.size} segments, ${totalBytes / 1024} KB")
            Result.Success(outputFile, validSegments.size, totalBytes)

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(TAG, "Recording cancelled at segment ${totalBytes / 1024} KB")
            Result.Cancelled(
                file = outputFile.takeIf { it.exists() && it.length() > 0 },
                segmentsCompleted = 0,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed", e)
            Result.Error("Recording failed: ${e.message}", e)
        }
    }

    /**
     * Strip ID3v2 header from MP3 data for concatenation.
     *
     * ID3v2 header: 10 bytes starting with "ID3" (0x49 0x44 0x33).
     * Size is a sync-safe integer in bytes 6-9.
     * Total header size = size + 10.
     *
     * Fallback: if ID3v2 not detected, look for MP3 frame sync (0xFF 0xFB).
     */
    private fun stripId3v2Header(data: ByteArray): ByteArray {
        if (data.size < 10) return data

        // Detect ID3v2
        if (data[0] == 0x49.toByte() && data[1] == 0x44.toByte() && data[2] == 0x33.toByte()) {
            val size = ((data[6].toInt() and 0x7F) shl 21) or
                       ((data[7].toInt() and 0x7F) shl 14) or
                       ((data[8].toInt() and 0x7F) shl 7) or
                       (data[9].toInt() and 0x7F)
            val headerSize = size + 10

            if (headerSize > 0 && headerSize < data.size) {
                Log.d(TAG, "  Stripped ID3v2 header: $headerSize bytes")
                return data.copyOfRange(headerSize, data.size)
            }
        }

        // Fallback: find first MP3 frame sync (0xFF 0xFB)
        for (i in 0 until (data.size - 1)) {
            if (data[i] == 0xFF.toByte() && (data[i + 1].toInt() and 0xFF) == 0xFB) {
                if (i > 0) Log.d(TAG, "  Found MP3 frame sync at offset $i, skipping header")
                return data.copyOfRange(i, data.size)
            }
        }

        return data
    }

    /** Get recordings directory for a book */
    fun getRecordingsDir(context: Context, bookId: String): File {
        return File(context.filesDir, "recordings/$bookId").also { it.mkdirs() }
    }

    /** Generate a recording filename */
    fun generateFilename(bookTitle: String, chapterLabel: String?): String {
        val ts = System.currentTimeMillis()
        val safeTitle = bookTitle.replace(Regex("[^\\w\\u4e00-\\u9fff]"), "_").take(30)
        val safeChapter = chapterLabel
            ?.replace(Regex("[^\\w\\u4e00-\\u9fff]"), "_")
            ?.take(25)
            ?: "full"
        return "${safeTitle}_${safeChapter}_${ts}.mp3"
    }
}
