package org.akanework.gramophone.logic.dj

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.setMediaItemsWithTitle
import java.io.File

class DjEngine(
    private val context: Context,
    private val playerProvider: () -> ExoPlayer?,
    private val apiKeyProvider: () -> String? = { null },
    private val endpointProvider: () -> String? = { null },
    private val modelProvider: () -> String? = { null }
) {
    companion object {
        private const val TAG = "DjEngine"
        @Volatile
        private var instance: DjEngine? = null

        fun getInstance(
            context: Context,
            playerProvider: () -> ExoPlayer?,
            apiKeyProvider: () -> String? = { null },
            endpointProvider: () -> String? = { null },
            modelProvider: () -> String? = { null }
        ): DjEngine {
            return instance ?: synchronized(this) {
                instance ?: DjEngine(
                    context.applicationContext,
                    playerProvider,
                    apiKeyProvider,
                    endpointProvider,
                    modelProvider
                ).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    val clusterer = DjBlockClusterer()
    val scriptGenerator = DjScriptGenerator(context, apiKeyProvider, endpointProvider, modelProvider)
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
        if (!enabled) {
            duckingCoordinator.cancelSpeechAndRestore()
        }
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
     * Start a DJ playback session immediately:
     * - If idle/stopped: cluster songs, populate queue, begin playback, and trigger first block intro speech.
     * - If already playing: cluster queue/songs and trigger speech over current song's intro.
     */
    fun startDjSession(controller: Player?, songs: List<MediaItem>) {
        setDjModeEnabled(true)
        if (songs.isEmpty()) {
            Log.w(TAG, "No songs available to start DJ session.")
            return
        }

        val isPlaying = controller?.isPlaying == true
        val currentItem = controller?.currentMediaItem

        if (!isPlaying || currentItem == null) {
            val blocks = clusterer.clusterTracks(songs)
            _currentBlocks.value = blocks
            _activeBlockIndex.value = 0

            val orderedSongs = blocks.flatMap { it.songs }.ifEmpty { songs }

            if (controller is MediaController) {
                controller.setMediaItemsWithTitle(
                    orderedSongs,
                    title = "DJ Livi Mix",
                    shuffleEnabled = false
                )
            } else {
                controller?.setMediaItems(orderedSongs)
            }
            controller?.prepare()
            controller?.play()

            blocks.firstOrNull()?.let { firstBlock ->
                triggerInterventionForBlock(firstBlock, previousSong = null)
            }
        } else {
            val currentQueue = mutableListOf<MediaItem>()
            val count = controller.mediaItemCount
            for (i in 0 until count) {
                currentQueue.add(controller.getMediaItemAt(i))
            }
            val pool = if (currentQueue.isNotEmpty()) currentQueue else songs
            setupQueue(pool)

            val currIdx = controller.currentMediaItemIndex
            val upcoming = if (currentQueue.size > currIdx + 1) {
                currentQueue.drop(currIdx + 1).take(3)
            } else {
                pool.filter { it.mediaId != currentItem.mediaId }.take(3)
            }

            triggerImmediateIntervention(
                currentSong = currentItem,
                upcomingSongs = upcoming
            )
        }
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

        if (_currentBlocks.value.isEmpty()) {
            playerProvider()?.let { player ->
                val count = player.mediaItemCount
                val queue = mutableListOf<MediaItem>()
                for (i in 0 until count) {
                    queue.add(player.getMediaItemAt(i))
                }
                if (queue.isNotEmpty()) {
                    setupQueue(queue)
                }
            }
        }

        val blocks = _currentBlocks.value
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
            val theme = if (currentSong != null) {
                val currentInfo = currentSong.toDjSongInfo()
                currentInfo.genre ?: currentInfo.artist
            } else {
                "nuestra selección musical"
            }
            executeIntervention(
                theme = theme,
                upcomingSongs = songInfoList,
                previousSong = currentSong?.toDjSongInfo()
            )
        }
    }

    /**
     * Public method to trigger speech for a specific block.
     */
    fun triggerInterventionForBlock(block: DjBlock, previousSong: DjSongInfo? = null) {
        scope.launch {
            val upcomingInfos = block.songs.map { it.toDjSongInfo() }
            executeIntervention(
                theme = block.title,
                upcomingSongs = upcomingInfos,
                previousSong = previousSong
            )
        }
    }

    /**
     * Test Livi's voice directly with speech ducking.
     */
    fun testVoice(sampleText: String = "¡Hola! Soy Livi, tu DJ personal. ¡Todo listo para arrancar con la mejor música!") {
        scope.launch {
            _lastSpeechScript.value = sampleText
            Log.i(TAG, "Testing DJ voice: $sampleText")
            val audioFile = ttsEngine.synthesizeToWavFile(sampleText)
            withContext(Dispatchers.Main) {
                duckingCoordinator.playInterventionWithDucking(
                    audioFile = audioFile,
                    fallbackText = sampleText
                ) {
                    Log.i(TAG, "DJ test voice playback completed.")
                    audioFile?.let { try { it.delete() } catch (ignored: Exception) {} }
                }
            }
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

            // 2. Synthesize Audio via Kokoro TTS (or null if weights missing)
            val audioFile = ttsEngine.synthesizeToWavFile(script)

            // 3. Duck audio and play speech (Kokoro WAV or native TTS fallback)
            withContext(Dispatchers.Main) {
                duckingCoordinator.playInterventionWithDucking(
                    audioFile = audioFile,
                    fallbackText = script
                ) {
                    Log.i(TAG, "DJ intervention completed successfully.")
                    audioFile?.let { try { it.delete() } catch (ignored: Exception) {} }
                }
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
