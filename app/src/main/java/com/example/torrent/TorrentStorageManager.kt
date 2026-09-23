package com.example.torrent

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

sealed class StorageSaveResult {
    data class Success(
        val permanentUri: Uri?,
        val permanentFile: File
    ) : StorageSaveResult()

    data class Failure(
        val reason: String
    ) : StorageSaveResult()
}

object TorrentStorageManager {
    private const val TAG = "TorrentStorageManager"

    /**
     * Copies the completed torrent temporary cache file to permanent user-accessible storage
     * using MediaStore (Android 10+) or public Downloads directory (Android 9 and below).
     *
     * Reports streaming progress, verifies file existence & size in user storage,
     * and deletes the temporary cache file ONLY after successful verification.
     */
    suspend fun saveToUserAccessibleStorage(
        context: Context,
        tempFile: File,
        originalFileName: String,
        onProgress: (progress: Float, writtenBytes: Long, totalBytes: Long) -> Unit
    ): StorageSaveResult = withContext(Dispatchers.IO) {
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            Log.e(TAG, "Geçici dosya bulunamadı veya boş: ${tempFile.absolutePath}")
            return@withContext StorageSaveResult.Failure("Dosya cihaz depolamasına kaydedilemedi.")
        }

        val totalBytes = tempFile.length()
        val safeFileName = sanitizeFileName(originalFileName.ifBlank { tempFile.name })
        val mimeType = resolveMimeType(safeFileName)

        var targetUri: Uri? = null
        var permanentFile: File? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Modern Android 10+ (API 29+): User-accessible MediaStore.Downloads
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RemSubs")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                targetUri = try {
                    context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore.Downloads ekleme başarısız, MediaStore.Video deneniyor: ${e.localizedMessage}")
                    null
                }

                if (targetUri == null) {
                    // Fallback to MediaStore.Video if Downloads collection fails
                    val videoValues = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, safeFileName)
                        put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RemSubs")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    targetUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoValues)
                }

                if (targetUri == null) {
                    Log.e(TAG, "MediaStore URI oluşturulamadı.")
                    return@withContext StorageSaveResult.Failure("Dosya cihaz depolamasına kaydedilemedi.")
                }

                // Stream from temporary cache file to MediaStore target
                var bytesWritten = 0L
                val buffer = ByteArray(256 * 1024)

                val outStream = context.contentResolver.openOutputStream(targetUri)
                if (outStream == null) {
                    try { context.contentResolver.delete(targetUri, null, null) } catch (_: Exception) {}
                    return@withContext StorageSaveResult.Failure("Dosya cihaz depolamasına kaydedilemedi.")
                }

                outStream.use { os ->
                    FileInputStream(tempFile).use { fis ->
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                            os.write(buffer, 0, bytesRead)
                            bytesWritten += bytesRead
                            val prog = (bytesWritten.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            onProgress(prog, bytesWritten, totalBytes)
                        }
                        os.flush()
                    }
                }

                // Publish: remove IS_PENDING so file appears in user file managers
                val publishValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(targetUri, publishValues, null, null)

                // Check for physical public file path
                val publicDownloadsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "RemSubs"
                )
                val expectedPublicFile = File(publicDownloadsDir, safeFileName)
                if (expectedPublicFile.exists() && expectedPublicFile.length() > 0L) {
                    permanentFile = expectedPublicFile
                }

            } else {
                // Legacy Android 9 and below: Use standard public Downloads folder
                val publicDownloadsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "RemSubs"
                ).apply { mkdirs() }

                val destFile = File(publicDownloadsDir, safeFileName)
                permanentFile = destFile

                var bytesWritten = 0L
                val buffer = ByteArray(256 * 1024)

                FileOutputStream(destFile).use { fos ->
                    FileInputStream(tempFile).use { fis ->
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                            bytesWritten += bytesRead
                            val prog = (bytesWritten.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            onProgress(prog, bytesWritten, totalBytes)
                        }
                        fos.flush()
                    }
                }

                targetUri = Uri.fromFile(destFile)
            }

            // --- STRICT VERIFICATION STEP ---
            var isVerified = false

            if (targetUri != null) {
                try {
                    context.contentResolver.openFileDescriptor(targetUri, "r")?.use { pfd ->
                        val size = pfd.statSize
                        if (size > 0L && Math.abs(size - totalBytes) <= 8192L) {
                            isVerified = true
                            Log.i(TAG, "MediaStore dosyası doğrulandı. Boyut: $size bytes")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "FileDescriptor doğrulama hatası: ${e.localizedMessage}")
                }
            }

            if (!isVerified && permanentFile != null && permanentFile.exists() && permanentFile.length() > 0L) {
                if (Math.abs(permanentFile.length() - totalBytes) <= 8192L) {
                    isVerified = true
                    Log.i(TAG, "Public dosya yolu doğrulandı. Boyut: ${permanentFile.length()} bytes")
                }
            }

            if (!isVerified) {
                Log.e(TAG, "Kalıcı depolama doğrulama başarısız! Dosya bulunamadı veya boyutu hatalı.")
                if (targetUri != null) {
                    try { context.contentResolver.delete(targetUri, null, null) } catch (_: Exception) {}
                }
                if (permanentFile != null && permanentFile.exists()) {
                    try { permanentFile.delete() } catch (_: Exception) {}
                }
                return@withContext StorageSaveResult.Failure("Dosya cihaz depolamasına kaydedilemedi.")
            }

            // Notify Android MediaScanner so user file managers and gallery index it immediately
            try {
                if (permanentFile != null && permanentFile.exists()) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(permanentFile.absolutePath),
                        arrayOf(mimeType),
                        null
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "MediaScanner uyarısı: ${t.localizedMessage}")
            }

            // SUCCESS: Only delete the temporary cache file AFTER permanent copy is verified!
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                    Log.i(TAG, "Geçici indirme önbellek dosyası güvenle temizlendi: ${tempFile.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Geçici dosya silinirken uyarı: ${e.localizedMessage}")
            }

            val finalFile = permanentFile ?: tempFile

            StorageSaveResult.Success(
                permanentUri = targetUri,
                permanentFile = finalFile
            )

        } catch (t: Throwable) {
            Log.e(TAG, "Kalıcı depolamaya aktarma sırasında hata: ${t.localizedMessage}", t)
            if (targetUri != null) {
                try { context.contentResolver.delete(targetUri, null, null) } catch (_: Exception) {}
            }
            if (permanentFile != null && permanentFile.exists()) {
                try { permanentFile.delete() } catch (_: Exception) {}
            }
            StorageSaveResult.Failure("Dosya cihaz depolamasına kaydedilemedi.")
        }
    }

    private fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val hasExt = clean.endsWith(".mp4", ignoreCase = true) ||
                clean.endsWith(".mkv", ignoreCase = true) ||
                clean.endsWith(".avi", ignoreCase = true) ||
                clean.endsWith(".webm", ignoreCase = true)
        return if (hasExt) clean else "$clean.mp4"
    }

    private fun resolveMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            fileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
            fileName.endsWith(".avi", ignoreCase = true) -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }
}
