package org.akanework.gramophone.logic.dj

import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class DjDuckingCoordinator(
    private val context: Context,
    private val playerProvider: () -> ExoPlayer?
) {
    companion object {
        private const val TAG = "DjDuckingCoordinator"
        const val DUCKED_VOLUME = 0.25f
        const val NORMAL_VOLUME = 1.0f
        const val RAMP_DURATION_MS = 400L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var speechPlayer: MediaPlayer? = null
    private var volumeAnimator: ValueAnimator? = null

    private val _duckingState = MutableStateFlow(DjDuckingState.IDLE)
    val duckingState: StateFlow<DjDuckingState> = _duckingState.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    fun playInterventionWithDucking(
        audioFile: File,
        onComplete: (() -> Unit)? = null
    ) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.w(TAG, "Audio file does not exist or is empty: ${audioFile.absolutePath}")
            onComplete?.invoke()
            return
        }

        val exoPlayer = playerProvider()
        if (exoPlayer == null) {
            Log.w(TAG, "ExoPlayer instance is null, cannot duck.")
            playSpeechDirectly(audioFile, onComplete)
            return
        }

        // 1. Duck ExoPlayer volume smoothly
        duckExoPlayer(exoPlayer) {
            // 2. Play speech
            playSpeechAudio(audioFile) {
                // 3. Restore ExoPlayer volume smoothly
                restoreExoPlayer(exoPlayer) {
                    onComplete?.invoke()
                }
            }
        }
    }

    private fun duckExoPlayer(exoPlayer: ExoPlayer, onDucked: () -> Unit) {
        _duckingState.value = DjDuckingState.DUCKING_ACTIVE
        volumeAnimator?.cancel()

        val startVol = exoPlayer.volume
        volumeAnimator = ValueAnimator.ofFloat(startVol, DUCKED_VOLUME).apply {
            duration = RAMP_DURATION_MS
            addUpdateListener { animator ->
                val v = animator.animatedValue as Float
                exoPlayer.volume = v
            }
            start()
        }

        mainHandler.postDelayed({
            onDucked()
        }, RAMP_DURATION_MS)
    }

    private fun restoreExoPlayer(exoPlayer: ExoPlayer, onRestored: () -> Unit) {
        _duckingState.value = DjDuckingState.RESTORING
        volumeAnimator?.cancel()

        val startVol = exoPlayer.volume
        volumeAnimator = ValueAnimator.ofFloat(startVol, NORMAL_VOLUME).apply {
            duration = RAMP_DURATION_MS
            addUpdateListener { animator ->
                val v = animator.animatedValue as Float
                exoPlayer.volume = v
            }
            start()
        }

        mainHandler.postDelayed({
            _duckingState.value = DjDuckingState.IDLE
            _isSpeaking.value = false
            onRestored()
        }, RAMP_DURATION_MS)
    }

    private fun playSpeechAudio(audioFile: File, onFinished: () -> Unit) {
        _isSpeaking.value = true
        releaseSpeechPlayer()

        try {
            speechPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .build()
                )
                setDataSource(context, Uri.fromFile(audioFile))
                setOnCompletionListener {
                    releaseSpeechPlayer()
                    onFinished()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer speech error: what=$what extra=$extra")
                    releaseSpeechPlayer()
                    onFinished()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech playback", e)
            releaseSpeechPlayer()
            onFinished()
        }
    }

    private fun playSpeechDirectly(audioFile: File, onFinished: (() -> Unit)?) {
        playSpeechAudio(audioFile) {
            onFinished?.invoke()
        }
    }

    fun cancelSpeechAndRestore() {
        releaseSpeechPlayer()
        playerProvider()?.let { exo ->
            restoreExoPlayer(exo) {}
        }
    }

    private fun releaseSpeechPlayer() {
        try {
            speechPlayer?.stop()
            speechPlayer?.release()
        } catch (ignored: Exception) {}
        speechPlayer = null
    }

    fun release() {
        cancelSpeechAndRestore()
        volumeAnimator?.cancel()
        volumeAnimator = null
    }
}
