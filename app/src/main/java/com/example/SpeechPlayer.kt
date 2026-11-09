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

    fun play(text: String, accent: String, speed: Float, gender: String = "female") {
        if (closed) return
        stop()
        val english = englishSpeech(text)
        if (english.isBlank()) return
        val request = generation
        val pack = if (gender == "female") "$accent-female" else accent
        val rate = speed.coerceIn(0.5f, 2f)
        if (playLocal(english, pack, rate, request) {}) return
        val chunks = speechChunks(english)
        fun next(index: Int) {
            if (generation != request || closed || index >= chunks.size) return
            val chunk = chunks[index]
            if (!playLocal(chunk, pack, rate, request) { next(index + 1) }) {
                val url = Uri.parse(BuildConfig.API_BASE + "/api/audio").buildUpon()
                    .appendQueryParameter("text", chunk).appendQueryParameter("accent", accent)
                    .appendQueryParameter("gender", gender).appendQueryParameter("speed", rate.toString()).build().toString()
                playMedia(request, onDone = { next(index + 1) }, onFailure = ::failed) { setDataSource(url) }
            }
        }
        next(0)
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
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    fun close() {
        stop()
        closed = true
    }
}

internal fun englishSpeech(text: String): String = text.lineSequence()
    .filterNot { line -> line.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }
    .joinToString(" ").replace(Regex("\\s+"), " ").trim()

internal fun speechChunks(text: String): List<String> {
    val chunks = mutableListOf<String>()
    var current = ""
    for (word in text.split(Regex("\\s+"))) {
        if (current.length + word.length + 1 > 650) {
            if (current.isNotBlank()) chunks += current
            current = ""
        }
        current += if (current.isEmpty()) word else " $word"
    }
    if (current.isNotBlank()) chunks += current
    return chunks
}
