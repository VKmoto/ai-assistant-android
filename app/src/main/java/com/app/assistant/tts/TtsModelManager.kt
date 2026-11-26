package com.app.assistant.tts

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Скачивание и хранение офлайн-голосов (русские Piper-модели из VoiceCatalog).
 *
 * Раньше здесь была одна жёстко заданная модель Supertonic (английская). Теперь
 * каталог: у каждого голоса свой архив и свой каталог, поэтому «скачать ещё голос»
 * не затирает уже скачанные и не требует переустановки приложения.
 *
 * Архив распаковывается системным `tar -xjf`: bz2 на устройстве разбирать нечем, а
 * бинарник tar есть в Android с древних версий. После распаковки в baseDir появляется
 * папка dirName с model.onnx, tokens.txt и espeak-ng-data.
 */
class TtsModelManager(private val context: Context) {

    private val baseDir = File(context.filesDir, "sherpa-onnx-tts")

    private var currentCall: okhttp3.Call? = null
    private val isCancelled = AtomicBoolean(false)

    fun modelDir(voiceId: String): File = File(baseDir, VoiceCatalog.byId(voiceId).dirName)

    fun getModelPath(voiceId: String): String =
        File(modelDir(voiceId), VoiceCatalog.byId(voiceId).modelFile).absolutePath

    fun getTokensPath(voiceId: String): String = File(modelDir(voiceId), "tokens.txt").absolutePath

    /** Piper фонемизирует через espeak-ng; данные лежат внутри архива голоса. */
    fun getDataDir(voiceId: String): String = File(modelDir(voiceId), "espeak-ng-data").absolutePath

    fun isModelDownloaded(voiceId: String): Boolean {
        val voice = VoiceCatalog.byId(voiceId)
        val dir = File(baseDir, voice.dirName)
        val model = File(dir, voice.modelFile)
        val tokens = File(dir, "tokens.txt")
        val espeak = File(dir, "espeak-ng-data")
        return model.exists() && model.length() > 0 &&
            tokens.exists() && tokens.length() > 0 &&
            espeak.isDirectory
    }

    fun installedVoices(): Set<String> =
        VoiceCatalog.voices.filter { isModelDownloaded(it.id) }.map { it.id }.toSet()

    fun isAnyModelDownloaded(): Boolean = installedVoices().isNotEmpty()

    fun deleteLocalModel(voiceId: String): Boolean = deleteDirectory(modelDir(voiceId))

    private fun deleteDirectory(dir: File): Boolean {
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { deleteDirectory(it) }
        }
        return dir.delete()
    }

    suspend fun downloadModel(
        client: OkHttpClient,
        voiceId: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onComplete: (success: Boolean, errorMessage: String?) -> Unit,
    ) {
        val voice = VoiceCatalog.byId(voiceId)
        isCancelled.set(false)
        withContext(Dispatchers.IO) {
            val tempArchive = File(context.cacheDir, "${voice.dirName}.tar.bz2")
            try {
                if (tempArchive.exists()) tempArchive.delete()
                if (!baseDir.exists()) baseDir.mkdirs()

                // Старые файлы этого же голоса не должны попасть в новую распаковку.
                deleteLocalModel(voiceId)

                val request = Request.Builder().url(voice.archiveUrl).build()
                val call = client.newCall(request)
                currentCall = call
                if (isCancelled.get()) throw IOException("Download cancelled by user")

                val response = call.execute()
                if (!response.isSuccessful) throw IOException("HTTP error code: ${response.code}")
                val body = response.body ?: throw IOException("Empty response body")
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(tempArchive).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (isCancelled.get()) throw IOException("Download cancelled by user")
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, totalBytes)
                        }
                        output.flush()
                    }
                }
                response.close()

                if (isCancelled.get()) throw IOException("Download cancelled by user")
                onProgress(totalBytes, totalBytes)
                val extracted = extractTarBz2(tempArchive, baseDir)
                if (extracted && isModelDownloaded(voiceId)) {
                    onComplete(true, null)
                } else {
                    onComplete(false, "Не удалось распаковать голос. Проверьте свободное место.")
                }
            } catch (e: Exception) {
                Log.e("TtsModelManager", "Error downloading voice $voiceId", e)
                val msg = if (isCancelled.get()) "Загрузка отменена." else e.message ?: "Неизвестная ошибка"
                onComplete(false, msg)
            } finally {
                currentCall = null
                if (tempArchive.exists()) tempArchive.delete()
            }
        }
    }

    fun cancelDownload() {
        isCancelled.set(true)
        currentCall?.cancel()
        currentCall = null
    }

    private fun extractTarBz2(archiveFile: File, targetDir: File): Boolean {
        targetDir.mkdirs()
        return try {
            val process = ProcessBuilder()
                .command("tar", "-xjf", archiveFile.absolutePath, "-C", targetDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exitCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.exitValue()
                } else {
                    process.destroyForcibly()
                    -1
                }
            } else {
                process.waitFor()
            }
            if (exitCode != 0) {
                Log.e("TtsModelManager", "tar failed ($exitCode): ${process.inputStream.bufferedReader().readText()}")
            }
            exitCode == 0
        } catch (e: Exception) {
            Log.e("TtsModelManager", "Failed to run native tar command", e)
            false
        }
    }
}
