package com.example.encode

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.example.R
import com.example.model.SubtitleCue
import com.example.model.SubtitleStyle
import com.example.parser.AssGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

data class EncodeState(
    val isPreparing: Boolean = false,
    val isEncoding: Boolean = false,
    val isCompleted: Boolean = false,
    val isCancelled: Boolean = false,
    val currentPhaseText: String = "Hazır",
    val currentEncoderName: String = "libx264",
    val progress: Float = 0f, // 0.0 to 1.0
    val progressPercentage: Int = 0, // 0 to 100
    val currentFrame: Long = 0L,
    val totalFrames: Long = 0L,
    val currentFps: Double = 0.0,
    val fpsToTotalFramesRatio: Double = 0.0,
    val estimatedRemainingSeconds: Long = 0L,
    val averageEstimatedFinishText: String = "--:--",
    val elapsedSeconds: Long = 0L,
    val outputVideoFile: File? = null,
    val errorMessage: String? = null,
    val lastLogLine: String = "",
    val fullLogs: String = "",
    val sourceMetadata: SourceVideoMetadata = SourceVideoMetadata()
)

enum class SubtitleFilterMode {
    ASS_WITH_FONTSDIR,
    SUBTITLES_FILTER,
    ASS_DIRECT
}

data class EncodeCandidate(
    val option: EncoderOption,
    val ffmpegEncoderName: String,
    val filterMode: SubtitleFilterMode
)

object HardsubEncoder {
    private const val TAG = "HardsubEncoder"

    private val _encodeState = MutableStateFlow(EncodeState())
    val encodeState: StateFlow<EncodeState> = _encodeState.asStateFlow()

    private var currentSession: FFmpegSession? = null
    private var startTimeMs: Long = 0L

    fun isFFmpegAvailable(): Boolean {
        return try {
            val version = FFmpegKitConfig.getVersion()
            Log.d(TAG, "FFmpegKit version: $version")
            version != null
        } catch (t: Throwable) {
            Log.e(TAG, "FFmpegKit not available: ${t.localizedMessage}", t)
            false
        }
    }

    fun resetState() {
        try {
            currentSession?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Error cancelling session during reset: ${t.localizedMessage}")
        }
        currentSession = null
        _encodeState.value = EncodeState()
    }

    fun cancelEncoding() {
        try {
            currentSession?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Error cancelling encode: ${t.localizedMessage}")
        }
        _encodeState.update {
            it.copy(
                isEncoding = false,
                isCancelled = true,
                currentPhaseText = "İşlem iptal edildi",
                errorMessage = "Encode kullanıcı tarafından iptal edildi."
            )
        }
    }

    /**
     * Entry point to frame-accurate REAL HARDSUB encoding.
     * Decodes every frame -> applies ASS/SRT via libass -> encodes rendered frames.
     */
    suspend fun startHardsubEncode(
        context: Context,
        videoUri: Uri,
        cues: List<SubtitleCue>,
        style: SubtitleStyle,
        customFontFile: File? = null,
        additionalStyles: List<String> = emptyList(),
        settings: EncodingSettings = EncodingSettings()
    ) = withContext(Dispatchers.IO) {
        val logBuffer = StringBuilder()

        try {
            _encodeState.update {
                EncodeState(
                    isPreparing = true,
                    currentPhaseText = "Video hazırlanıyor...",
                    errorMessage = null
                )
            }

            Log.i(TAG, "Video hazırlanıyor...")

            // 0. Pre-check: Verify FFmpegKit native initialization
            if (!isFFmpegAvailable()) {
                val errorMsg = "FFmpegKit native kütüphanesi başlatılamadı. " +
                        "Cihaz mimarisi ile uyumlu native .so kütüphanesi yüklenemedi (arm64-v8a / x86_64)."
                Log.e(TAG, errorMsg)
                _encodeState.update {
                    it.copy(isPreparing = false, errorMessage = errorMsg)
                }
                return@withContext
            }

            cleanupOldTempFiles(context)

            // 1. Prepare input video file safely using streaming buffer (never load entire video into RAM)
            val inputVideoFile = prepareInputVideoFile(context, videoUri)
            if (inputVideoFile == null || !inputVideoFile.exists() || !inputVideoFile.canRead() || inputVideoFile.length() <= 0L) {
                val errorMsg = "Video açılamadı veya girdi dosyası boş (0 byte)."
                Log.e(TAG, errorMsg)
                _encodeState.update {
                    it.copy(isPreparing = false, errorMessage = errorMsg)
                }
                return@withContext
            }

            // 2. Analyze input video
            val sourceMetadata = InputVideoAnalyzer.analyzeVideo(context, inputVideoFile, videoUri)
            _encodeState.update { it.copy(sourceMetadata = sourceMetadata) }

            // Check disk space
            val usableSpace = context.cacheDir.usableSpace
            val requiredSpace = (inputVideoFile.length() * 1.5).toLong().coerceAtLeast(30L * 1024 * 1024)
            if (usableSpace < requiredSpace) {
                val reqMb = requiredSpace / (1024 * 1024)
                val availMb = usableSpace / (1024 * 1024)
                val errorMsg = "Yetersiz depolama alanı! Encode için en az ~$reqMb MB gerekli, mevcut alan: $availMb MB."
                Log.e(TAG, errorMsg)
                _encodeState.update {
                    it.copy(isPreparing = false, errorMessage = errorMsg)
                }
                return@withContext
            }

            // 3. Subtitle Preparation
            _encodeState.update { it.copy(currentPhaseText = "Altyazı hazırlanıyor...") }
            Log.i(TAG, "Altyazı hazırlanıyor...")

            // Configure fonts in libass fontconfig
            val fontsDir = configureFontsForLibass(context, customFontFile, style)

            // Generate precise ASS content matching video dimensions
            val videoWidth = if (sourceMetadata.width > 0) sourceMetadata.width else 1920
            val videoHeight = if (sourceMetadata.height > 0) sourceMetadata.height else 1080
            val assContent = AssGenerator.generateAss(
                title = "remsubs_hardsub",
                subtitles = cues,
                style = style,
                applyTimeOffset = false,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                additionalStyles = additionalStyles
            )

            val assDir = File(context.cacheDir, "subs").apply { mkdirs() }
            val assFile = File(assDir, "burn_in_subtitles_${System.currentTimeMillis()}.ass")
            assFile.writeText(assContent, Charsets.UTF_8)

            if (!assFile.exists() || !assFile.canRead() || assFile.length() <= 0L) {
                val errorMsg = "Altyazı uygulanamadı (.ass dosyası oluşturulamadı)."
                Log.e(TAG, errorMsg)
                _encodeState.update {
                    it.copy(isPreparing = false, errorMessage = errorMsg)
                }
                return@withContext
            }

            // 4. Output video file
            val outputFileName = "remsubs_hardsub_${System.currentTimeMillis()}.mp4"
            val outputFile = File(context.cacheDir, outputFileName)
            if (outputFile.exists()) outputFile.delete()

            // 5. Total frames calculation
            val (durationMs, videoFps) = extractVideoMetadata(inputVideoFile)
            val totalFrames = if (durationMs > 0) {
                ((durationMs / 1000.0) * videoFps).toLong().coerceAtLeast(1L)
            } else {
                900L
            }

            // 6. Dynamic Encoder Selection & Fallback Chain
            val (chosenEncoder, ffmpegEncoderName) = DeviceCodecDetector.resolveBestEncoder(settings, sourceMetadata)
            val candidateList = mutableListOf<EncodeCandidate>()
            candidateList.add(EncodeCandidate(chosenEncoder, ffmpegEncoderName, SubtitleFilterMode.ASS_WITH_FONTSDIR))
            if (chosenEncoder != EncoderOption.LIBX264) {
                candidateList.add(EncodeCandidate(EncoderOption.LIBX264, "libx264", SubtitleFilterMode.ASS_WITH_FONTSDIR))
            }
            candidateList.add(EncodeCandidate(EncoderOption.LIBX264, "libx264", SubtitleFilterMode.SUBTITLES_FILTER))
            candidateList.add(EncodeCandidate(EncoderOption.LIBX264, "libx264", SubtitleFilterMode.ASS_DIRECT))

            executeEncodeWithFallback(
                context = context,
                inputVideoFile = inputVideoFile,
                assFile = assFile,
                fontsDir = fontsDir,
                outputFile = outputFile,
                cues = cues,
                candidates = candidateList,
                candidateIndex = 0,
                settings = settings,
                totalFrames = totalFrames,
                logBuffer = logBuffer
            )

        } catch (t: Throwable) {
            Log.e(TAG, "Encode başlatılırken kritik hata yakalandı", t)
            val errorMsg = "Encode başlatılamadı: ${t.localizedMessage ?: t.javaClass.simpleName}"
            _encodeState.update {
                it.copy(
                    isPreparing = false,
                    isEncoding = false,
                    isCompleted = false,
                    errorMessage = errorMsg,
                    fullLogs = logBuffer.toString() + "\n" + t.stackTraceToString()
                )
            }
        }
    }

    private fun configureFontsForLibass(
        context: Context,
        customFontFile: File?,
        style: SubtitleStyle
    ): File {
        val fontsDir = File(context.cacheDir, "fonts").apply { mkdirs() }
        val fontDirs = mutableListOf<String>()

        val systemFonts = File("/system/fonts")
        if (systemFonts.exists() && systemFonts.isDirectory) {
            fontDirs.add(systemFonts.absolutePath)
        }
        val systemFontAlt = File("/system/font")
        if (systemFontAlt.exists() && systemFontAlt.isDirectory) {
            fontDirs.add(systemFontAlt.absolutePath)
        }
        val appCustomFonts = File(context.filesDir, "custom_fonts").apply { mkdirs() }
        fontDirs.add(appCustomFonts.absolutePath)
        fontDirs.add(fontsDir.absolutePath)

        val fontMap = mutableMapOf<String, String>()

        // Find default Roboto / system font
        val defaultSystemFont = systemFonts.listFiles()?.firstOrNull { file ->
            val name = file.name.lowercase(Locale.ROOT)
            name.endsWith(".ttf") && (name.contains("roboto-regular") || name.contains("roboto") || name.contains("notosans") || name.contains("droidsans"))
        } ?: systemFonts.listFiles()?.firstOrNull { it.name.endsWith(".ttf", ignoreCase = true) }

        val defaultFontName = defaultSystemFont?.name ?: "Roboto-Regular.ttf"

        fontMap["Arial"] = defaultFontName
        fontMap["arial"] = defaultFontName
        fontMap["sans-serif"] = defaultFontName
        fontMap["Sans-serif"] = defaultFontName
        fontMap["Default"] = defaultFontName
        fontMap["default"] = defaultFontName
        fontMap["Roboto"] = defaultFontName
        fontMap["roboto"] = defaultFontName

        if (customFontFile != null && customFontFile.exists()) {
            try {
                val destFont = File(fontsDir, customFontFile.name)
                if (!destFont.exists() || destFont.length() != customFontFile.length()) {
                    customFontFile.copyTo(destFont, overwrite = true)
                }
                fontMap[customFontFile.nameWithoutExtension] = destFont.name
                fontMap[customFontFile.name] = destFont.name
                fontMap[style.fontName] = destFont.name
                fontMap["Default"] = destFont.name
                fontMap["Arial"] = destFont.name
                Log.i(TAG, "Özel TTF font kaydedildi ve eşlendi: ${destFont.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Özel font kopyalanamadı: ${e.localizedMessage}")
            }
        }

        try {
            FFmpegKitConfig.setFontDirectoryList(context, fontDirs, fontMap)
            Log.i(TAG, "FFmpegKit fontconfig yapılandırıldı: ${fontDirs.size} font dizini yüklendi.")
        } catch (t: Throwable) {
            Log.w(TAG, "setFontDirectoryList çağrılırken hata/uyarı: ${t.localizedMessage}")
        }

        return fontsDir
    }

    private fun executeEncodeWithFallback(
        context: Context,
        inputVideoFile: File,
        assFile: File,
        fontsDir: File,
        outputFile: File,
        cues: List<SubtitleCue>,
        candidates: List<EncodeCandidate>,
        candidateIndex: Int,
        settings: EncodingSettings,
        totalFrames: Long,
        logBuffer: StringBuilder
    ) {
        if (candidateIndex >= candidates.size) {
            val errorMsg = "Altyazı videoya gömülemedi."
            Log.e(TAG, errorMsg)
            _encodeState.update {
                it.copy(
                    isPreparing = false,
                    isEncoding = false,
                    isCompleted = false,
                    errorMessage = errorMsg
                )
            }
            return
        }

        val candidate = candidates[candidateIndex]
        val candidateOption = candidate.option
        val candidateFfmpegName = candidate.ffmpegEncoderName

        Log.i(TAG, "Subtitle renderer başlatılıyor... (Mod: ${candidate.filterMode})")
        Log.i(TAG, "Encoder başlatılıyor... (${candidateOption.displayName} - $candidateFfmpegName)")
        Log.i(TAG, "Video kareleri işleniyor...")
        Log.i(TAG, "Altyazı karelere uygulanıyor...")

        _encodeState.update {
            it.copy(
                isPreparing = false,
                isEncoding = true,
                isCompleted = false,
                isCancelled = false,
                currentPhaseText = "Video kareleri işleniyor...",
                currentEncoderName = candidateOption.displayName,
                totalFrames = totalFrames,
                progress = 0f,
                progressPercentage = 0,
                currentFps = 0.0,
                fpsToTotalFramesRatio = 0.0,
                averageEstimatedFinishText = "Hesaplanıyor...",
                errorMessage = null
            )
        }

        startTimeMs = System.currentTimeMillis()

        // Build command arguments with clean filter escaping and no softsubs
        val commandArgs = buildSafeCommandArgs(
            inputVideoFile = inputVideoFile,
            assFile = assFile,
            fontsDir = fontsDir,
            outputFile = outputFile,
            candidate = candidate,
            settings = settings
        )

        Log.d(TAG, "=== FFmpeg Hardsub Pipeline (${candidateOption.displayName} / ${candidate.filterMode}) ===")
        Log.d(TAG, "1. Girdi: ${inputVideoFile.absolutePath}")
        Log.d(TAG, "2. Altyazı: ${assFile.absolutePath}")
        Log.d(TAG, "3. Font Dizini: ${fontsDir.absolutePath}")
        Log.d(TAG, "4. Çıktı: ${outputFile.absolutePath}")
        Log.d(TAG, "5. Komut: ${commandArgs.joinToString(" ")}")

        val session = FFmpegKit.executeWithArgumentsAsync(
            commandArgs,
            { completedSession ->
                val returnCode = completedSession.returnCode
                val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000L
                val allLogs = completedSession.allLogsAsString ?: logBuffer.toString()

                Log.d(TAG, "FFmpeg Return Code: ${returnCode?.value}")
                Log.i(TAG, "Encode tamamlanıyor...")

                if (ReturnCode.isSuccess(returnCode)) {
                    Log.i(TAG, "Çıktı doğrulanıyor...")
                    _encodeState.update { it.copy(currentPhaseText = "Çıktı doğrulanıyor...") }

                    val validation = validateHardsubOutput(
                        outputFile = outputFile,
                        inputVideoFile = inputVideoFile,
                        cues = cues,
                        allLogs = allLogs
                    )

                    when (validation) {
                        is ValidationResult.Success -> {
                            Log.i(TAG, "Hardsub doğrulaması başarılı! Boyut: ${outputFile.length()} bytes")
                            _encodeState.update {
                                it.copy(
                                    isEncoding = false,
                                    isCompleted = true,
                                    currentPhaseText = "Encode tamamlandı",
                                    progress = 1f,
                                    progressPercentage = 100,
                                    currentFrame = totalFrames,
                                    elapsedSeconds = elapsed,
                                    averageEstimatedFinishText = "Tamamlandı (00:00)",
                                    outputVideoFile = outputFile,
                                    fullLogs = allLogs
                                )
                            }
                        }
                        is ValidationResult.Failure -> {
                            Log.e(TAG, "Hardsub doğrulaması başarısız oldu: ${validation.reason}")
                            if (outputFile.exists()) outputFile.delete()

                            val nextIndex = candidateIndex + 1
                            if (nextIndex < candidates.size) {
                                val nextCandidate = candidates[nextIndex]
                                Log.w(TAG, "Doğrulama başarısız oldu, alternatif deneniyor: ${nextCandidate.option.displayName} (${nextCandidate.filterMode})")
                                _encodeState.update {
                                    it.copy(
                                        currentPhaseText = "Alternatif encoder deneniyor: ${nextCandidate.option.displayName}..."
                                    )
                                }
                                executeEncodeWithFallback(
                                    context = context,
                                    inputVideoFile = inputVideoFile,
                                    assFile = assFile,
                                    fontsDir = fontsDir,
                                    outputFile = outputFile,
                                    cues = cues,
                                    candidates = candidates,
                                    candidateIndex = nextIndex,
                                    settings = settings,
                                    totalFrames = totalFrames,
                                    logBuffer = logBuffer
                                )
                            } else {
                                _encodeState.update {
                                    it.copy(
                                        isEncoding = false,
                                        isCompleted = false,
                                        currentPhaseText = "Hata oluştu",
                                        elapsedSeconds = elapsed,
                                        errorMessage = validation.reason,
                                        fullLogs = allLogs
                                    )
                                }
                            }
                        }
                    }

                } else if (ReturnCode.isCancel(returnCode)) {
                    Log.w(TAG, "Encode kullanıcı tarafından iptal edildi.")
                    if (outputFile.exists()) outputFile.delete()
                    _encodeState.update {
                        it.copy(
                            isEncoding = false,
                            isCancelled = true,
                            currentPhaseText = "İşlem iptal edildi",
                            elapsedSeconds = elapsed,
                            errorMessage = "İşlem iptal edildi.",
                            fullLogs = allLogs
                        )
                    }
                } else {
                    // Check if fallback candidate is available
                    val nextIndex = candidateIndex + 1
                    if (nextIndex < candidates.size) {
                        val nextCandidate = candidates[nextIndex]
                        Log.w(TAG, "Encoder başarısız oldu (${candidateOption.displayName}), alternatif deneniyor: ${nextCandidate.option.displayName} (${nextCandidate.filterMode})")
                        _encodeState.update {
                            it.copy(
                                currentPhaseText = "Encoder başarısız oldu, alternatif deneniyor: ${nextCandidate.option.displayName}..."
                            )
                        }
                        if (outputFile.exists()) outputFile.delete()

                        executeEncodeWithFallback(
                            context = context,
                            inputVideoFile = inputVideoFile,
                            assFile = assFile,
                            fontsDir = fontsDir,
                            outputFile = outputFile,
                            cues = cues,
                            candidates = candidates,
                            candidateIndex = nextIndex,
                            settings = settings,
                            totalFrames = totalFrames,
                            logBuffer = logBuffer
                        )
                    } else {
                        val returnCodeVal = returnCode?.value ?: -1
                        val errorMsg = "Altyazı videoya gömülemedi."
                        Log.e(TAG, "FFmpeg Error (Hata Kodu: $returnCodeVal): $errorMsg\n${allLogs.takeLast(1000)}")
                        if (outputFile.exists()) outputFile.delete()
                        _encodeState.update {
                            it.copy(
                                isEncoding = false,
                                isCompleted = false,
                                currentPhaseText = "Hata oluştu",
                                elapsedSeconds = elapsed,
                                errorMessage = errorMsg,
                                fullLogs = allLogs
                            )
                        }
                    }
                }
            },
            { log ->
                val message = log.message ?: ""
                logBuffer.append(message).append("\n")
                _encodeState.update { it.copy(lastLogLine = message.takeLast(120)) }
            },
            { stats: Statistics ->
                handleStatistics(stats, totalFrames)
            }
        )

        currentSession = session
    }

    private fun buildSafeCommandArgs(
        inputVideoFile: File,
        assFile: File,
        fontsDir: File,
        outputFile: File,
        candidate: EncodeCandidate,
        settings: EncodingSettings
    ): Array<String> {
        val escapedAssPath = escapeFilterPath(assFile.absolutePath)
        val escapedFontsDir = escapeFilterPath(fontsDir.absolutePath)
        val filterString = when (candidate.filterMode) {
            SubtitleFilterMode.ASS_WITH_FONTSDIR -> "ass=filename='$escapedAssPath':fontsdir='$escapedFontsDir'"
            SubtitleFilterMode.SUBTITLES_FILTER -> "subtitles=filename='$escapedAssPath':fontsdir='$escapedFontsDir'"
            SubtitleFilterMode.ASS_DIRECT -> "ass=filename='$escapedAssPath'"
        }

        val args = mutableListOf<String>()
        args.add("-y")
        args.add("-i")
        args.add(inputVideoFile.absolutePath)

        // Subtitle filter: Burns subtitle directly into decoded video frames
        args.add("-vf")
        args.add(filterString)

        // Video codec
        args.add("-c:v")
        args.add(candidate.ffmpegEncoderName)

        val encoderOption = candidate.option
        if (encoderOption.isHardware) {
            val targetBitrate = if (settings.bitrate != "Otomatik") settings.bitrate else encoderOption.defaultBitrate
            args.add("-b:v")
            args.add(targetBitrate)
        } else {
            if (encoderOption.supportsCrf) {
                args.add("-crf")
                args.add(settings.crf.toString())
            }
            if (encoderOption.supportsPreset) {
                args.add("-preset")
                args.add(settings.preset)
            }
            if (settings.bitrate != "Otomatik") {
                args.add("-b:v")
                args.add(settings.bitrate)
            }
        }

        // Pixel format
        val pixFmt = if (settings.pixelFormat != "Otomatik") settings.pixelFormat else "yuv420p"
        args.add("-pix_fmt")
        args.add(pixFmt)

        // Multi-threading for software codecs
        if (!encoderOption.isHardware) {
            args.add("-threads")
            args.add("0")
        }

        // Custom FPS if specified
        if (settings.fps != "Kaynakla Aynı") {
            args.add("-r")
            args.add(settings.fps)
        }

        // Map streams: First video, optional audio, and EXPLICITLY DISABLE soft subtitles (-sn)
        args.add("-map")
        args.add("0:v:0")
        args.add("-map")
        args.add("0:a?")
        args.add("-c:a")
        args.add("aac")
        args.add("-b:a")
        args.add("192k")

        // CRITICAL: Prevent soft subtitle streams from leaking into output
        args.add("-sn")

        args.add(outputFile.absolutePath)

        return args.toTypedArray()
    }

    private fun handleStatistics(stats: Statistics, totalFrames: Long) {
        val frameNum = stats.videoFrameNumber.toLong().coerceAtLeast(0L)
        val fps = stats.videoFps.toDouble().coerceAtLeast(0.0)
        val elapsedSec = ((System.currentTimeMillis() - startTimeMs) / 1000L).coerceAtLeast(1L)

        val progress = if (totalFrames > 0) {
            (frameNum.toFloat() / totalFrames.toFloat()).coerceIn(0f, 0.99f)
        } else {
            0f
        }

        val percentage = (progress * 100).toInt()

        val fpsToTotalFramesRatio = if (totalFrames > 0) {
            fps / totalFrames.toDouble()
        } else {
            0.0
        }

        val remainingFrames = (totalFrames - frameNum).coerceAtLeast(0L)
        val estimatedRemainingSeconds: Long = if (fps > 1.0) {
            (remainingFrames / fps).toLong()
        } else if (progress > 0.05f) {
            val totalEstimatedSec = (elapsedSec / progress).toLong()
            (totalEstimatedSec - elapsedSec).coerceAtLeast(0L)
        } else {
            0L
        }

        val finishTimeText = formatTimeSeconds(estimatedRemainingSeconds)

        _encodeState.update {
            it.copy(
                progress = progress,
                progressPercentage = percentage,
                currentFrame = frameNum,
                currentFps = fps,
                fpsToTotalFramesRatio = fpsToTotalFramesRatio,
                estimatedRemainingSeconds = estimatedRemainingSeconds,
                averageEstimatedFinishText = finishTimeText,
                elapsedSeconds = elapsedSec,
                currentPhaseText = "Video kareleri işleniyor (Kare: $frameNum / $totalFrames)..."
            )
        }
    }

    fun formatTimeSeconds(totalSeconds: Long): String {
        val mins = totalSeconds / 60
        val secs = totalSeconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    private fun prepareInputVideoFile(context: Context, videoUri: Uri): File? {
        return try {
            val tempInput = File(context.cacheDir, "input_for_hardsub_${System.currentTimeMillis()}.mp4")
            if (tempInput.exists()) tempInput.delete()

            var inputStream: InputStream? = null

            if (videoUri.scheme == "android.resource" || videoUri.toString().contains("sample_demo")) {
                try {
                    inputStream = context.resources.openRawResource(R.raw.sample_demo)
                } catch (e: Exception) {
                    Log.w(TAG, "Raw kaynak doğrudan açılamadı: ${e.localizedMessage}")
                }
            }

            if (inputStream == null) {
                inputStream = if (videoUri.scheme == "file") {
                    videoUri.path?.let { path ->
                        val f = File(path)
                        if (f.exists() && f.canRead()) FileInputStream(f) else null
                    }
                } else {
                    context.contentResolver.openInputStream(videoUri)
                }
            }

            if (inputStream == null) {
                Log.e(TAG, "Girdi akışı açılamadı: $videoUri")
                return null
            }

            inputStream.use { input ->
                FileOutputStream(tempInput).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }

            if (tempInput.exists() && tempInput.length() > 0L) tempInput else null
        } catch (t: Throwable) {
            Log.e(TAG, "Girdi videosu kopyalanırken hata: ${t.localizedMessage}", t)
            null
        }
    }

    private fun cleanupOldTempFiles(context: Context) {
        try {
            val cache = context.cacheDir
            cache.listFiles()?.forEach { file ->
                if (file.name.startsWith("input_for_hardsub_") || file.name.startsWith("burn_in_subtitles_")) {
                    file.delete()
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Proper escaping for FFmpeg filter arguments enclosed in single quotes.
     * Inside single quotes, only backslashes and single quotes must be escaped.
     */
    private fun escapeFilterPath(path: String): String {
        return path
            .replace("\\", "\\\\")
            .replace("'", "\\'")
    }

    private fun extractVideoMetadata(file: File): Pair<Long, Double> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 30_000L

            val fpsStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            } else null
            val videoFps = fpsStr?.toDoubleOrNull() ?: 30.0
            Pair(durationMs, videoFps)
        } catch (e: Exception) {
            Pair(30_000L, 30.0)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    private fun areFilesIdentical(file1: File, file2: File): Boolean {
        if (!file1.exists() || !file2.exists()) return false
        if (file1.length() != file2.length()) return false
        return try {
            val b1 = ByteArray(8192)
            val b2 = ByteArray(8192)
            FileInputStream(file1).use { f1 ->
                FileInputStream(file2).use { f2 ->
                    var r1: Int
                    var r2: Int
                    do {
                        r1 = f1.read(b1)
                        r2 = f2.read(b2)
                        if (r1 != r2) return false
                        if (r1 > 0) {
                            for (i in 0 until r1) {
                                if (b1[i] != b2[i]) return false
                            }
                        }
                    } while (r1 != -1)
                    true
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun validateHardsubOutput(
        outputFile: File,
        inputVideoFile: File,
        cues: List<SubtitleCue>,
        allLogs: String
    ): ValidationResult {
        // 1. Output file must exist and have valid size
        if (!outputFile.exists() || outputFile.length() < 1024L) {
            Log.e(TAG, "Çıktı video dosyası oluşturulamadı veya boş.")
            return ValidationResult.Failure("Altyazı videoya gömülemedi.")
        }

        // 2. Output file must not be identical path or binary to source
        if (outputFile.canonicalPath == inputVideoFile.canonicalPath ||
            (outputFile.length() == inputVideoFile.length() && areFilesIdentical(outputFile, inputVideoFile))) {
            Log.e(TAG, "Orijinal kaynak video çıktı olarak kabul edilemez.")
            return ValidationResult.Failure("Altyazı videoya gömülemedi.")
        }

        // 3. Verify subtitle filter actually ran from logs
        val lowerLogs = allLogs.lowercase(Locale.ROOT)
        val filterExecuted = lowerLogs.contains("parsed_ass") ||
                lowerLogs.contains("parsed_subtitles") ||
                lowerLogs.contains("filter ass") ||
                lowerLogs.contains("subtitles filter") ||
                lowerLogs.contains("auto_scaler") ||
                lowerLogs.contains("ass: ") ||
                lowerLogs.contains("libass") ||
                lowerLogs.contains("[ass @")

        val filterError = lowerLogs.contains("could not initialize libass") ||
                lowerLogs.contains("failed to configure filter") ||
                lowerLogs.contains("no such filter: 'ass'") ||
                lowerLogs.contains("cannot load fontconfig") ||
                lowerLogs.contains("error initializing filter 'ass'") ||
                lowerLogs.contains("error initializing filter 'subtitles'") ||
                lowerLogs.contains("null filter graph") ||
                lowerLogs.contains("error reinitializing filters")

        if (filterError || (!filterExecuted && cues.isNotEmpty())) {
            Log.e(TAG, "Altyazı filtre doğrulama hatası! filterExecuted=$filterExecuted, filterError=$filterError")
            return ValidationResult.Failure("Altyazı videoya gömülemedi.")
        }

        // 4. MediaMetadataRetriever decode verification
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(outputFile.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

            val duration = durationStr?.toLongOrNull() ?: 0L
            val width = widthStr?.toIntOrNull() ?: 0
            val height = heightStr?.toIntOrNull() ?: 0

            if (hasVideo != "yes" && width <= 0) {
                Log.e(TAG, "Çıktı dosyasında geçerli video akışı bulunamadı.")
                return ValidationResult.Failure("Altyazı videoya gömülemedi.")
            }
            if (duration <= 0L) {
                Log.e(TAG, "Çıktı videosunun süresi geçersiz: $duration ms")
                return ValidationResult.Failure("Altyazı videoya gömülemedi.")
            }

            // Test decoding a frame at subtitle timestamp or middle of video
            val testTimestampUs = if (cues.isNotEmpty()) {
                val firstCue = cues.first()
                val midTime = (firstCue.startTimeMs + firstCue.endTimeMs) / 2
                midTime.coerceAtLeast(0L) * 1000L
            } else {
                (duration / 2).coerceAtLeast(0L) * 1000L
            }

            val frameBitmap = try {
                retriever.getFrameAtTime(testTimestampUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L)
            } catch (e: Exception) {
                null
            }

            if (frameBitmap == null) {
                Log.e(TAG, "Çıktı videosunun kareleri çözümlenemiyor.")
                return ValidationResult.Failure("Altyazı videoya gömülemedi.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "MediaMetadataRetriever exception: ${e.localizedMessage}")
            return ValidationResult.Failure("Altyazı videoya gömülemedi.")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        return ValidationResult.Success
    }

    private sealed class ValidationResult {
        object Success : ValidationResult()
        data class Failure(val reason: String) : ValidationResult()
    }
}
