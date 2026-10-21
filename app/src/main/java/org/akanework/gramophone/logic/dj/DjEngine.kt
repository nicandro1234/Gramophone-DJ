package org.akanework.gramophone.logic.dj

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DjEngine(
    private val context: Context,
    private val playerProvider: () -> ExoPlayer?,
    private val apiKeyProvider: () -> String? = { null }
) {
    companion object {
        private const val TAG = "DjEngine"
        @Volatile
        private var instance: DjEngine? = null

        fun getInstance(
            context: Context,
            playerProvider: () -> ExoPlayer?,
            apiKeyProvider: () -> String? = { null }
        ): DjEngine {
            return instance ?: synchronized(this) {
                instance ?: DjEngine(context.applicationContext, playerProvider, apiKeyProvider).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    val clusterer = DjBlockClusterer()
    val scriptGenerator = DjScriptGenerator(context, apiKeyProvider)
    val ttsEngine = DjTtsEngine(context)
    val duckingCoordinator = DjDuckingCoordinator(context, playerProvider)

    private val _isDjModeEnabled = MutableStateFlow(false)
    val isDjModeEnabled: StateFlow<Boolean> = _isDjModeEnabled.asStateFlow()

    private val _currentBlocks = MutableStateFlow<List<DjBlock>>(emptyList())
    val currentBlocks: StateFlow<List<DjBlock>> = _currentBlocks.asStateFlow()

    private val _activeBlockIndex = MutableStateFlow(0)
    val activeBlockIndex: StateFlow<Int> = _activeBlockIndex.asStateFlow()

    private val _lastSpeechScript = MutableStateFlow<String?>(null)
    val lastSpeechScript: StateFlow<String?> = _lastSpeechScript.asStateFlow()

    private var previousSongInfo: DjSongInfo? = null

    fun setDjModeEnabled(enabled: Boolean) {
        _isDjModeEnabled.value = enabled
        Log.i(TAG, "DJ Mode set to: $enabled")
    }

    fun setupQueue(songs: List<MediaItem>) {
        if (songs.isEmpty()) return
        val blocks = clusterer.clusterTracks(songs)
        _currentBlocks.value = blocks
        _activeBlockIndex.value = 0
        Log.i(TAG, "Generated ${blocks.size} DJ blocks from ${songs.size} tracks")
    }

    /**
     * Triggered when a new song transition occurs in the player.
     * Checks if this song represents the beginning of a new DJ block.
     */
    fun onSongTransition(currentMediaItem: MediaItem?) {
        if (!_isDjModeEnabled.value || currentMediaItem == null) {
            previousSongInfo = currentMediaItem?.toDjSongInfo()
            return
        }

        val blocks = _currentBlocks.value
        val currentIndex = _activeBlockIndex.value
        if (blocks.isEmpty()) return

        // Find which block contains this song
        val targetBlockIndex = blocks.indexOfFirst { block ->
            block.songs.any { it.mediaId == currentMediaItem.mediaId }
        }

        if (targetBlockIndex != -1) {
            val block = blocks[targetBlockIndex]
            // If this song is the FIRST song of the block, trigger DJ speech!
            if (block.songs.firstOrNull()?.mediaId == currentMediaItem.mediaId) {
                _activeBlockIndex.value = targetBlockIndex
                triggerInterventionForBlock(block, previousSongInfo)
            }
        }

        previousSongInfo = currentMediaItem.toDjSongInfo()
    }

    /**
     * Trigger an on-demand DJ intervention (e.g. from UI button).
     */
    fun triggerImmediateIntervention(currentSong: MediaItem?, upcomingSongs: List<MediaItem>) {
        scope.launch {
            val songInfoList = upcomingSongs.take(3).map { it.toDjSongInfo() }
            val theme = if (currentSong != null) "este momento especial" else "nuestra programación"
            executeIntervention(
                theme = theme,
                upcomingSongs = songInfoList,
                previousSong = currentSong?.toDjSongInfo()
            )
        }
    }

    private fun triggerInterventionForBlock(block: DjBlock, previousSong: DjSongInfo?) {
        scope.launch {
            val upcomingInfos = block.songs.map { it.toDjSongInfo() }
            executeIntervention(
                theme = block.title,
                upcomingSongs = upcomingInfos,
                previousSong = previousSong
            )
        }
    }

    private suspend fun executeIntervention(
        theme: String,
        upcomingSongs: List<DjSongInfo>,
        previousSong: DjSongInfo?
    ) = withContext(Dispatchers.IO) {
        try {
            // 1. Generate Script
            val script = scriptGenerator.generateSpeech(
                upcomingSongs = upcomingSongs,
                blockTheme = theme,
                previousSong = previousSong
            )
            _lastSpeechScript.value = script
            Log.i(TAG, "Generated DJ Script: $script")

            // 2. Synthesize Audio via Kokoro TTS
            val audioFile = ttsEngine.synthesizeToWavFile(script)
            if (audioFile != null && audioFile.exists()) {
                withContext(Dispatchers.Main) {
                    // 3. Duck audio and play speech
                    duckingCoordinator.playInterventionWithDucking(audioFile) {
                        Log.i(TAG, "DJ intervention completed successfully.")
                        // Clean up temporary audio file after play
                        try { audioFile.delete() } catch (ignored: Exception) {}
                    }
                }
            } else {
                Log.w(TAG, "TTS audio synthesis returned null, skipping voice intervention.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing DJ intervention", e)
        }
    }

    fun release() {
        duckingCoordinator.release()
        ttsEngine.release()
        instance = null
    }
}
