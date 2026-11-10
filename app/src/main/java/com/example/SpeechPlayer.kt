package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale

/** English recordings and an explicitly English synthesizer share the same voice selection. */
internal class SpeechPlayer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val indices = mutableMapOf<String, JSONObject>()
    private var generation = 0
    private var player: MediaPlayer? = null
    private var closed = false
    private val downloads = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val cache = java.io.File(context.cacheDir, "natural-speech-v2").apply { mkdirs() }
    private var download: java.util.concurrent.Future<*>? = null

    fun play(text: String, accent: String, speed: Float, gender: String = "female") {
        if (closed) return
        stop()
        val english = englishSpeech(text)
        if (english.isBlank()) return
        val request = generation
        val pack = if (gender == "female") "$accent-female" else accent
        val rate = speed.coerceIn(0.5f, 2f)
        val natural = context.getSharedPreferences("lexiflow_preferences", 0).getBoolean("natural_speech", true)
        if (!natural && playLocal(english, pack, rate, request) {}) return
        val chunks = speechChunks(english)
        var warned = false
        fun next(index: Int) {
            if (generation != request || closed || index >= chunks.size) return
            val chunk = chunks[index]
            val done = { next(index + 1) }
            val fallback = {
                if (!warned) {
                    Toast.makeText(context, "自然语音暂不可用，尝试离线发音", Toast.LENGTH_SHORT).show()
                    warned = true
                }
                if (!playLocal(chunk, pack, rate, request, done)) failed()
            }
            if (!natural && playLocal(chunk, pack, rate, request, done)) return
            playNatural(chunk, accent, gender, rate, request, done, fallback, onReady = {
                if (index + 1 < chunks.size) {
                    playNatural(chunks[index + 1], accent, gender, rate, request, {}, {}, prefetch = true)
                }
            })
        }
        next(0)
    }

    private fun playNatural(text: String, accent: String, gender: String, rate: Float, request: Int,
                            done: () -> Unit, fallback: () -> Unit, prefetch: Boolean = false, onReady: () -> Unit = {}) {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest("$accent|$gender|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = java.io.File(cache, "$key.audio")
        if (file.exists()) {
            if (prefetch) return
            file.setLastModified(System.currentTimeMillis())
            playMedia(request, rate, done, {
                file.delete(); fallback()
            }) { setDataSource(file.absolutePath) }
            onReady()
            return
        }
        download = downloads.submit {
            var ready = false
            try {
                val uri = Uri.parse(BuildConfig.API_BASE + "/api/audio").buildUpon()
                    .appendQueryParameter("text", text).appendQueryParameter("accent", accent)
                    .appendQueryParameter("gender", gender).appendQueryParameter("speed", "1").build()
                val connection = java.net.URL(uri.toString()).openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 20000
                    val keyFile = java.io.File(context.noBackupFilesDir, "study-key")
                    if (keyFile.exists()) {
                        val stamp = (System.currentTimeMillis() / 1000).toString()
                        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                        mac.init(javax.crypto.spec.SecretKeySpec(keyFile.readText().trim().toByteArray(), "HmacSHA256"))
                        val path = uri.encodedPath + "?" + uri.encodedQuery
                        val signature = mac.doFinal((stamp + "\n" + path).toByteArray()).joinToString("") { "%02x".format(it) }
                        connection.setRequestProperty("X-Study-Time", stamp)
                        connection.setRequestProperty("X-Study-Signature", signature)
                    }
                    check(connection.responseCode == 200)
                    check(connection.getHeaderField("X-Speech-Engine") in listOf("kokoro", "tencent"))
                    val mime = connection.contentType.orEmpty().substringBefore(';')
                    check(mime in listOf("audio/wav", "audio/mpeg"))
                    val temp = java.io.File(cache, "$key.tmp")
                    try {
                        connection.inputStream.use { input -> temp.outputStream().use { out ->
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                total += count
                                check(total <= 8_000_000 && !Thread.currentThread().isInterrupted)
                                out.write(buffer, 0, count)
                            }
                            check(total > 100)
                        } }
                        check(temp.renameTo(file))
                        ready = true
                        val files = cache.listFiles()?.filter { it.extension == "audio" }.orEmpty()
                        var size = files.sumOf { it.length() }
                        for (old in files.sortedBy { it.lastModified() }) {
                            if (size <= 64_000_000) break
                            if (old != file) { size -= old.length(); old.delete() }
                        }
                    } finally { temp.delete() }
                } finally { connection.disconnect() }
            } catch (_: Exception) { }
            val success = ready
            main.post {
                if (request == generation && !closed && !prefetch) {
                    if (success) {
                        playMedia(request, rate, done, fallback) { setDataSource(file.absolutePath) }
                        onReady()
                    }
                    else fallback()
                }
            }
        }
    }

    private fun playLocal(text: String, pack: String, rate: Float, request: Int, done: () -> Unit): Boolean {
        val range = try {
            indices.getOrPut(pack) {
                context.assets.open("audio/$pack.json").bufferedReader().use { JSONObject(it.readText()) }
            }.optJSONArray(text.trim().lowercase(Locale.ROOT))
        } catch (_: Exception) { null } ?: return false
        playMedia(request, rate, done, ::failed) {
            context.assets.openFd("audio/$pack.lfpack").use {
                setDataSource(it.fileDescriptor, it.startOffset + range.getLong(0), range.getLong(1))
            }
        }
        return true
    }

    private fun playMedia(
        request: Int, rate: Float = 1f, onDone: () -> Unit, onFailure: () -> Unit,
        source: MediaPlayer.() -> Unit
    ) {
        if (generation != request || closed) return
        player?.release()
        val mp = MediaPlayer()
        player = mp
        val timeout = Runnable { if (generation == request && player == mp) onFailure() }
        try {
            mp.setAudioAttributes(attributes)
            mp.setOnPreparedListener {
                if (generation == request && player == it) {
                    main.removeCallbacks(timeout)
                    runCatching {
                        if (rate != 1f) it.playbackParams = it.playbackParams.setSpeed(rate)
                        it.start()
                    }.onFailure { onFailure() }
                }
            }
            mp.setOnCompletionListener {
                if (generation == request && player == it) {
                    it.release()
                    player = null
                    onDone()
                }
            }
            mp.setOnErrorListener { failed, _, _ ->
                main.removeCallbacks(timeout)
                if (generation == request && player == failed) onFailure()
                true
            }
            mp.source()
            mp.prepareAsync()
            main.postDelayed(timeout, 20000)
        } catch (_: Exception) {
            main.removeCallbacks(timeout)
            if (generation == request) onFailure()
        }
    }

    private fun failed() {
        stop()
        Toast.makeText(context, "朗读未能播放，请检查网络或稍后重试。", Toast.LENGTH_LONG).show()
    }

    fun stop() {
        generation++
        download?.cancel(true)
        download = null
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    fun close() {
        stop()
        closed = true
        downloads.shutdownNow()
    }
}

internal fun englishSpeech(text: String): String = text.lineSequence()
    .filterNot { line -> line.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }
    .joinToString(" ").replace(Regex("\\s+"), " ").trim()

internal fun speechChunks(text: String): List<String> {
    val chunks = mutableListOf<String>()
    var current = ""
    for (word in text.split(Regex("\\s+"))) {
        if (current.length + word.length + 1 > 350) {
            if (current.isNotBlank()) chunks += current
            current = ""
        }
        current += if (current.isEmpty()) word else " $word"
    }
    if (current.isNotBlank()) chunks += current
    return chunks
}
