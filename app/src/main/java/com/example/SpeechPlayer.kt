package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.util.Locale

internal class SpeechPlayer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private data class Request(val id: String, val text: String, val accent: String, val speed: Float)
    private val indices = mutableMapOf<String, org.json.JSONObject>()
    private var generation = 0L
    private var current: Request? = null
    private var player: MediaPlayer? = null
    private var ready: Boolean? = null
    private var closed = false
    private val engine = TextToSpeech(context.applicationContext) { status ->
        main.post { initialized(status == TextToSpeech.SUCCESS) }
    }

    private fun initialized(ok: Boolean) {
        if (closed) return
        ready = ok
        if (ok) {
            engine.setAudioAttributes(attributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) = main.postFor(id) { main.removeCallbacks(watchdog) }
                override fun onDone(id: String) = main.postFor(id) { finish() }
                @Deprecated("Android TTS callback")
                override fun onError(id: String) = main.postFor(id) { fallback() }
            })
        }
        current?.let { if (player == null) start(it) }
    }

    private fun Handler.postFor(id: String, action: () -> Unit) {
        post { if (current?.id == id && player == null) action() }
    }

    private val watchdog = Runnable { fallback() }
    private val networkTimeout = Runnable { failed() }

    fun play(text: String, accent: String, speed: Float) {
        if (text.isBlank() || closed) return
        stop()
        val request = Request((++generation).toString(), text, accent, speed.coerceIn(0.5f, 2f))
        current = request
        if (!playPack(request)) start(request)
    }

    private fun playPack(request: Request): Boolean {
        val accent = if (request.accent == "UK") "UK" else "US"
        return try {
            val index = indices.getOrPut(accent) {
                context.assets.open("audio/$accent.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
            }
            val range = index.optJSONArray(request.text.trim().lowercase(Locale.ROOT)) ?: return false
            val local = MediaPlayer()
            player = local
            local.setAudioAttributes(attributes)
            context.assets.openFd("audio/$accent.lfpack").use { fd ->
                local.setDataSource(fd.fileDescriptor, fd.startOffset + range.getLong(0), range.getLong(1))
            }
            fun retry() {
                if (player != local) return
                local.release()
                player = null
                start(request)
            }
            local.setOnPreparedListener { mp ->
                if (player == mp) {
                    runCatching {
                        mp.playbackParams = mp.playbackParams.setSpeed(request.speed)
                        mp.start()
                    }.onFailure { retry() }
                }
            }
            local.setOnCompletionListener { if (player == it) finish() }
            local.setOnErrorListener { _, _, _ -> retry(); true }
            local.prepareAsync()
            true
        } catch (_: Exception) {
            player?.release()
            player = null
            false
        }
    }

    private fun start(request: Request) {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 4000)
        if (ready == null) return
        val locale = if (request.accent == "UK") Locale.UK else Locale.US
        if (ready == true && engine.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) {
            engine.setSpeechRate(request.speed)
            if (engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, request.id) == TextToSpeech.SUCCESS) return
        }
        fallback()
    }

    private fun fallback() {
        val request = current ?: return
        if (player != null) return
        main.removeCallbacks(watchdog)
        val url = Uri.parse(BuildConfig.API_BASE + "/api/audio").buildUpon()
            .appendQueryParameter("text", request.text)
            .appendQueryParameter("accent", request.accent)
            .appendQueryParameter("speed", request.speed.toString()).build().toString()
        try {
            val remote = MediaPlayer()
            player = remote
            engine.stop()
            remote.setAudioAttributes(attributes)
            remote.setOnPreparedListener {
                if (player == it) {
                    main.removeCallbacks(networkTimeout)
                    runCatching { it.start() }.onFailure { failed() }
                }
            }
            remote.setOnCompletionListener { if (player == it) finish() }
            remote.setOnErrorListener { mp, _, _ -> if (player == mp) failed(); true }
            main.postDelayed(networkTimeout, 15000)
            remote.setDataSource(url)
            remote.prepareAsync()
        } catch (_: Exception) {
            failed()
        }
    }

    private fun finish() {
        current = null
        main.removeCallbacks(watchdog)
        main.removeCallbacks(networkTimeout)
        player?.release()
        player = null
    }

    private fun failed() {
        finish()
        Toast.makeText(context, "朗读失败。请检查网络，或在系统语音设置中安装英语语音后重试。", Toast.LENGTH_LONG).show()
    }

    fun stop() {
        finish()
        if (ready == true) engine.stop()
    }

    fun close() {
        closed = true
        stop()
        engine.shutdown()
    }
}
