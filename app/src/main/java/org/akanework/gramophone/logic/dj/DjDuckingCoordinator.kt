package org.akanework.gramophone.logic.dj

import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

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

    private var systemTts: TextToSpeech? = null
    private var isSystemTtsReady = false

    private val _duckingState = MutableStateFlow(DjDuckingState.IDLE)
    val duckingState: StateFlow<DjDuckingState> = _duckingState.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        initSystemTts()
    }

    private fun initSystemTts() {
        try {
            systemTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isSystemTtsReady = true
                    val tts = systemTts
                    val locAr = Locale("es", "AR")
                    val result = tts?.setLanguage(locAr)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale("es"))
                    }
                    tts?.setPitch(1.05f)
                    tts?.setSpeechRate(1.0f)
                    Log.i(TAG, "Native Android TextToSpeech initialized successfully.")
                } else {
                    Log.w(TAG, "Native Android TextToSpeech init returned code: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize native Android TextToSpeech", e)
        }
    }

    fun playInterventionWithDucking(
        audioFile: File?,
        fallbackText: String? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val hasAudio = audioFile != null && audioFile.exists() && audioFile.length() > 0L
        val hasFallback = !fallbackText.isNullOrBlank()

        if (!hasAudio && !hasFallback) {
            Log.w(TAG, "Neither audio file nor fallback text available for DJ intervention.")
            onComplete?.invoke()
            return
        }

        val exoPlayer = playerProvider()
        if (exoPlayer == null || !exoPlayer.isPlaying) {
            Log.i(TAG, "Player is idle/null, speaking directly without ducking.")
            if (hasAudio) {
                playSpeechAudio(audioFile!!) { onComplete?.invoke() }
            } else {
                playFallbackSystemSpeech(fallbackText!!) { onComplete?.invoke() }
            }
            return
        }

        // 1. Duck ExoPlayer volume smoothly
        duckExoPlayer(exoPlayer) {
            val finishAction: () -> Unit = {
                // 3. Restore ExoPlayer volume smoothly
                restoreExoPlayer(exoPlayer) {
                    onComplete?.invoke()
                }
            }

            // 2. Play speech audio (Kokoro WAV or native TTS fallback)
            if (hasAudio) {
                playSpeechAudio(audioFile!!, finishAction)
            } else {
                playFallbackSystemSpeech(fallbackText!!, finishAction)
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

    private fun playFallbackSystemSpeech(text: String, onFinished: () -> Unit) {
        _isSpeaking.value = true

        val tts = systemTts
        if (tts == null || !isSystemTtsReady) {
            Log.w(TAG, "System TTS not ready yet, attempting reinit...")
            initSystemTts()
            // Even if not ready, release speaking flag after a delay to not lock volume
            mainHandler.postDelayed({
                _isSpeaking.value = false
                onFinished()
            }, 500L)
            return
        }

        val utteranceId = "dj_livi_${System.currentTimeMillis()}"
        var finishDispatched = false
        val safeFinish: () -> Unit = {
            if (!finishDispatched) {
                finishDispatched = true
                _isSpeaking.value = false
                mainHandler.post { onFinished() }
            }
        }

        val timeoutRunnable = Runnable {
            Log.w(TAG, "System TTS speech timed out after 15 seconds")
            safeFinish()
        }
        mainHandler.postDelayed(timeoutRunnable, 15000L)

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                Log.d(TAG, "System TTS speech started: $id")
            }

            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    safeFinish()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    Log.e(TAG, "System TTS speech error: $id")
                    mainHandler.removeCallbacks(timeoutRunnable)
                    safeFinish()
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) {
                    Log.e(TAG, "System TTS speech error (code $errorCode): $id")
                    mainHandler.removeCallbacks(timeoutRunnable)
                    safeFinish()
                }
            }
        })

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech speak() failed with code: $result")
            mainHandler.removeCallbacks(timeoutRunnable)
            safeFinish()
        }
    }

    fun cancelSpeechAndRestore() {
        releaseSpeechPlayer()
        try {
            systemTts?.stop()
        } catch (ignored: Exception) {}
        _isSpeaking.value = false
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
        try {
            systemTts?.shutdown()
        } catch (ignored: Exception) {}
        systemTts = null
        isSystemTtsReady = false
    }
}
