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
import android.speech.tts.Voice
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.preference.PreferenceManager
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
        const val DEFAULT_DUCKED_VOLUME = 0.07f
        const val NORMAL_VOLUME = 1.0f
        const val RAMP_DURATION_MS = 250L
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

    private fun initSystemTts(onReady: ((TextToSpeech?) -> Unit)? = null) {
        try {
            systemTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isSystemTtsReady = true
                    systemTts?.let { tts ->
                        applyBestVoice(tts)
                        tts.setPitch(1.05f)
                        tts.setSpeechRate(1.02f)
                    }
                    Log.i(TAG, "Native Android TextToSpeech initialized successfully.")
                    onReady?.invoke(systemTts)
                } else {
                    Log.w(TAG, "Native Android TextToSpeech init returned code: $status")
                    onReady?.invoke(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize native Android TextToSpeech", e)
            onReady?.invoke(null)
        }
    }

    fun applyBestVoice(tts: TextToSpeech) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val selectedVoiceName = prefs.getString("dj_tts_voice", null)

        val voices = try { tts.voices } catch (e: Exception) { null }
        if (!voices.isNullOrEmpty()) {
            if (!selectedVoiceName.isNullOrBlank() && selectedVoiceName != "auto") {
                val matched = voices.find { it.name == selectedVoiceName }
                if (matched != null) {
                    try {
                        tts.voice = matched
                        Log.i(TAG, "Applied user selected TTS voice: ${matched.name}")
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set user voice, falling back to auto", e)
                    }
                }
            }

            val bestVoice = findBestSpanishVoice(voices)
            if (bestVoice != null) {
                try {
                    tts.voice = bestVoice
                    Log.i(TAG, "Applied best Spanish voice: ${bestVoice.name} (${bestVoice.locale})")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set best voice", e)
                }
            }
        }

        val locAr = Locale.forLanguageTag("es-AR")
        val result = tts.setLanguage(locAr)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.forLanguageTag("es"))
        }
    }

    private fun findBestSpanishVoice(voices: Set<Voice>): Voice? {
        val spanishVoices = voices.filter { voice ->
            val lang = voice.locale.language
            lang.equals("es", ignoreCase = true) &&
                !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }

        if (spanishVoices.isEmpty()) return null

        return spanishVoices.maxByOrNull { voice ->
            var score = 0
            val tag = voice.locale.toLanguageTag().lowercase()
            val name = voice.name.lowercase()

            // Argentine Spanish preferred, then Latin American, then other Spanish
            if (tag.contains("ar")) score += 1000
            else if (tag.contains("us") || tag.contains("419") || tag.contains("mx")) score += 600
            else score += 200

            // Quality
            score += when (voice.quality) {
                Voice.QUALITY_VERY_HIGH -> 800
                Voice.QUALITY_HIGH -> 400
                Voice.QUALITY_NORMAL -> 100
                else -> 0
            }

            // Neural / Network / High-fidelity indicators (significantly less robotic)
            if (name.contains("network")) score += 500
            if (name.contains("neural")) score += 500
            if (name.contains("natural")) score += 400
            if (name.contains("premium")) score += 300
            if (name.contains("sfb")) score += 200
            if (name.contains("female")) score += 150

            score
        }
    }

    fun getAvailableSpanishVoices(onResult: (List<Voice>) -> Unit) {
        val tts = systemTts
        if (tts != null && isSystemTtsReady) {
            val list = try {
                tts.voices?.filter { it.locale.language.equals("es", ignoreCase = true) }
                    ?.sortedByDescending { it.quality } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            onResult(list)
        } else {
            initSystemTts { readyTts ->
                val list = try {
                    readyTts?.voices?.filter { it.locale.language.equals("es", ignoreCase = true) }
                        ?.sortedByDescending { it.quality } ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
                onResult(list)
            }
        }
    }

    private fun getDuckingVolume(): Float {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val levelStr = prefs.getString("dj_ducking_level", "0.07")
        return levelStr?.toFloatOrNull() ?: DEFAULT_DUCKED_VOLUME
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
                playSpeechAudio(audioFile) { onComplete?.invoke() }
            } else {
                playFallbackSystemSpeech(fallbackText!!) { onComplete?.invoke() }
            }
            return
        }

        // 1. Duck ExoPlayer volume smoothly and aggressively
        duckExoPlayer(exoPlayer) {
            val finishAction: () -> Unit = {
                // 3. Restore ExoPlayer volume smoothly
                restoreExoPlayer(exoPlayer) {
                    onComplete?.invoke()
                }
            }

            // 2. Play speech audio (Kokoro WAV or native TTS fallback)
            if (hasAudio) {
                playSpeechAudio(audioFile, finishAction)
            } else {
                playFallbackSystemSpeech(fallbackText!!, finishAction)
            }
        }
    }

    private fun duckExoPlayer(exoPlayer: ExoPlayer, onDucked: () -> Unit) {
        _duckingState.value = DjDuckingState.DUCKING_ACTIVE
        volumeAnimator?.cancel()

        val startVol = exoPlayer.volume
        val targetVol = getDuckingVolume()
        volumeAnimator = ValueAnimator.ofFloat(startVol, targetVol).apply {
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
            mainHandler.postDelayed({
                _isSpeaking.value = false
                onFinished()
            }, 500L)
            return
        }

        // Apply latest voice preferences
        applyBestVoice(tts)

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
