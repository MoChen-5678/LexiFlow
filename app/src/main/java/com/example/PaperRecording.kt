package com.example

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun PaperRecording(urls: List<String>) = key(urls) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var loading by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var route by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    fun release() {
        player?.release()
        player = null
        playing = false
        loading = false
        duration = 0
        position = 0f
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) release()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); release() }
    }
    LaunchedEffect(playing) {
        while (playing) {
            if (!dragging) position = (player?.currentPosition ?: 0).toFloat()
            delay(350)
        }
    }
    fun open(index: Int) {
        release()
        if (index >= urls.size) {
            error = true
            return
        }
        route = index
        loading = true
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setOnErrorListener { failed, _, _ ->
                if (player == failed) open(index + 1)
                true
            }
            mp.setOnPreparedListener {
                if (player == it) {
                    loading = false
                    duration = it.duration
                    runCatching { it.start(); playing = true }.onFailure { open(index + 1) }
                }
            }
            mp.setOnCompletionListener { if (player == it) { playing = false; it.seekTo(0); position = 0f } }
            mp.setDataSource(urls[index])
            mp.prepareAsync()
        } catch (_: Exception) {
            open(index + 1)
        }
    }
    LaunchedEffect(player, loading) {
        if (loading) {
            val attempt = player
            delay(15000)
            if (player == attempt && loading) open(route + 1)
        }
    }
    OutlinedButton(onClick = {
        error = false
        val existing = player
        if (existing == null) open(0) else {
            runCatching {
                if (playing) existing.pause() else existing.start()
                playing = !playing
            }.onFailure { open(route + 1) }
        }
    }, enabled = !loading) {
        Text(if (loading) "正在载入原卷录音…" else if (playing) "暂停录音" else "播放原卷录音")
    }
    if (error) Text("原卷录音未能加载，请检查网络后重试。")
    if (duration > 0) {
        fun clock(ms: Int) = String.format(Locale.ROOT, "%d:%02d", ms / 60000, ms / 1000 % 60)
        Text(clock(position.toInt()) + " / " + clock(duration))
        Slider(value = position.coerceIn(0f, duration.toFloat()), onValueChange = { dragging = true; position = it },
            onValueChangeFinished = { player?.seekTo(position.toInt()); dragging = false }, valueRange = 0f..duration.toFloat())
    }
}
