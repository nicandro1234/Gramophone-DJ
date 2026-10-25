package org.akanework.gramophone.logic.dj

import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.akanework.gramophone.logic.GramophonePlaybackService
import java.io.File
import java.util.Locale

class DjDuckingCoordinator(
    private val context: Context,
    private val playerProvider: () -> ExoPlayer? = { null }
) {
    companion object {
        private const val TAG = "DjDuckingCoordinator"
        const val DEFAULT_DUCKED_VOLUME = 0.07f
        const val NORMAL_VOLUME = 1.0f
        const val RAMP_DURATION_MS = 250L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var speechPlayer: MediaPlayer? = null
    private var volumeAnimator: ValueAnimator? = null
    private var pendingDuckingRunnable: Runnable? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var systemTts: TextToSpeech? = null
    private var isSystemTtsReady = false
    private var activeUtteranceId: String? = null

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

    private fun getActivePlayer(): Player? {
        val service = GramophonePlaybackService.instanceForWidgetAndLyricsOnly
        try {
            service?.endedWorkaroundPlayer?.let { return it }
            service?.endedWorkaroundPlayer?.exoPlayer?.let { return it }
            playerProvider()?.let { return it }
        } catch (_: Exception) {}

        return null
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

        val locMx = Locale.forLanguageTag("es-MX")
        val result = tts.setLanguage(locMx)
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

            if (tag.contains("mx")) score += 1200
            else if (tag.contains("419") || tag.contains("us")) score += 800
            else if (tag.contains("ar") || tag.contains("es")) score += 400
            else score += 200

            score += when (voice.quality) {
                Voice.QUALITY_VERY_HIGH -> 800
                Voice.QUALITY_HIGH -> 400
                Voice.QUALITY_NORMAL -> 100
                else -> 0
            }

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

    fun getDuckingVolume(): Float {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val levelStr = prefs.getString("dj_ducking_level", "0.07")
        return levelStr?.toFloatOrNull() ?: DEFAULT_DUCKED_VOLUME
    }

    fun prepareForIntervention() {
        val player = getActivePlayer()
        val targetVol = getDuckingVolume()
        _duckingState.value = DjDuckingState.DUCKING_ACTIVE
        _isSpeaking.value = true
        applyVolumeToPlayer(player, targetVol)
        Log.i(TAG, "prepareForIntervention: Ducked volume pre-emptively to $targetVol")
    }

    private fun requestTransientDuckingFocus(): Boolean {
        val am = audioManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "DJ audio focus changed: $focusChange")
                    }
                    .build()
                audioFocusRequest = request
                am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request transient ducking focus", e)
            false
        }
    }

    private fun abandonTransientDuckingFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon transient ducking focus", e)
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

        val player = getActivePlayer()

        // Request system-level audio ducking focus so Android OS attenuates music automatically
        requestTransientDuckingFocus()

        // Always duck the player (even if idle or buffering, so upcoming playback starts ducked)
        duckPlayer(player) {
            val finishAction: () -> Unit = {
                // Restore player volume smoothly and release focus
                restorePlayer(player) {
                    abandonTransientDuckingFocus()
                    onComplete?.invoke()
                }
            }

            // Play speech audio (Kokoro WAV or native TTS fallback)
            if (hasAudio) {
                playSpeechAudio(audioFile, finishAction)
            } else {
                playFallbackSystemSpeech(fallbackText!!, finishAction)
            }
        }
    }

    private fun duckPlayer(player: Player?, onDucked: () -> Unit) {
        _duckingState.value = DjDuckingState.DUCKING_ACTIVE
        _isSpeaking.value = true
        volumeAnimator?.cancel()

        val targetVol = getDuckingVolume()
        val startVol = player?.volume ?: NORMAL_VOLUME

        if (Math.abs(startVol - targetVol) < 0.01f) {
            applyVolumeToPlayer(player, targetVol)
            onDucked()
            return
        }

        volumeAnimator = ValueAnimator.ofFloat(startVol, targetVol).apply {
            duration = RAMP_DURATION_MS
            addUpdateListener { animator ->
                val v = animator.animatedValue as Float
                applyVolumeToPlayer(player, v)
            }
            start()
        }

        val duckRunnable = Runnable {
            pendingDuckingRunnable = null
            // Force final ducked volume at the end of the ramp
            applyVolumeToPlayer(player, targetVol)
            onDucked()
        }
        pendingDuckingRunnable = duckRunnable
        mainHandler.postDelayed(duckRunnable, RAMP_DURATION_MS)
    }

    private fun restorePlayer(player: Player?, onRestored: () -> Unit) {
        _duckingState.value = DjDuckingState.RESTORING
        volumeAnimator?.cancel()
        pendingDuckingRunnable?.let { mainHandler.removeCallbacks(it) }

        val startVol = player?.volume ?: getDuckingVolume()
        volumeAnimator = ValueAnimator.ofFloat(startVol, NORMAL_VOLUME).apply {
            duration = RAMP_DURATION_MS
            addUpdateListener { animator ->
                val v = animator.animatedValue as Float
                applyVolumeToPlayer(player, v)
            }
            start()
        }

        val restoreRunnable = Runnable {
            pendingDuckingRunnable = null
            applyVolumeToPlayer(player, NORMAL_VOLUME)
            _duckingState.value = DjDuckingState.IDLE
            _isSpeaking.value = false
            onRestored()
        }
        pendingDuckingRunnable = restoreRunnable
        mainHandler.postDelayed(restoreRunnable, RAMP_DURATION_MS)
    }

    fun applyVolumeToPlayer(player: Player?, volume: Float) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { applyVolumeToPlayer(player, volume) }
            return
        }

        try {
            val service = GramophonePlaybackService.instanceForWidgetAndLyricsOnly
            val exo = service?.endedWorkaroundPlayer?.exoPlayer ?: playerProvider()
            exo?.volume = volume
            service?.endedWorkaroundPlayer?.volume = volume
        } catch (e: Exception) {
            Log.w(TAG, "Error applying volume to exo player", e)
        }

        try {
            if (player !is ExoPlayer && player != null) {
                player.volume = volume
            }
        } catch (_: Exception) {}
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

        applyBestVoice(tts)

        val utteranceId = "dj_livi_${System.currentTimeMillis()}"
        activeUtteranceId = utteranceId
        var finishDispatched = false
        val safeFinish: () -> Unit = {
            if (!finishDispatched && activeUtteranceId == utteranceId) {
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
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech speak() failed with code: $result")
            mainHandler.removeCallbacks(timeoutRunnable)
            safeFinish()
        }
    }

    fun onPlaybackPaused() {
        Log.i(TAG, "onPlaybackPaused: stopping speech and restoring normal volume")
        cancelSpeechAndRestore()
    }

    fun cancelSpeechAndRestore() {
        volumeAnimator?.cancel()
        volumeAnimator = null
        pendingDuckingRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDuckingRunnable = null
        releaseSpeechPlayer()
        activeUtteranceId = null
        try {
            systemTts?.stop()
        } catch (ignored: Exception) {}
        _isSpeaking.value = false
        _duckingState.value = DjDuckingState.IDLE
        abandonTransientDuckingFocus()
        applyVolumeToPlayer(getActivePlayer(), NORMAL_VOLUME)
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
