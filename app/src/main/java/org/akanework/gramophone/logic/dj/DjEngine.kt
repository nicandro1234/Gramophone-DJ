package org.akanework.gramophone.logic.dj

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.parseQueueTitle
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
    private var lastIntervenedSongId: String? = null
    private var songsSinceLastIntervention = 0
    private var activeInterventionJob: Job? = null

    fun setDjModeEnabled(enabled: Boolean) {
        _isDjModeEnabled.value = enabled
        if (!enabled) {
            activeInterventionJob?.cancel()
            activeInterventionJob = null
            duckingCoordinator.cancelSpeechAndRestore()
        }
        Log.i(TAG, "DJ Mode set to: $enabled")
    }

    fun setupQueue(songs: List<MediaItem>, forceRecluster: Boolean = false) {
        if (songs.isEmpty()) return
        if (!forceRecluster && _currentBlocks.value.isNotEmpty()) {
            val currentTotal = _currentBlocks.value.sumOf { it.songs.size }
            if (currentTotal == songs.size) {
                Log.d(TAG, "setupQueue: blocks already clustered ($currentTotal tracks), preserving current block layout.")
                return
            }
        }
        val blocks = clusterer.clusterTracks(songs)
        _currentBlocks.value = blocks
        _activeBlockIndex.value = 0
        songsSinceLastIntervention = 0
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

        // 1. Pre-duck volume immediately so playback starts attenuated
        duckingCoordinator.prepareForIntervention()

        // 2. Always generate a fresh, dynamically randomized cluster of blocks
        val blocks = clusterer.clusterTracks(songs)
        _currentBlocks.value = blocks
        _activeBlockIndex.value = 0
        songsSinceLastIntervention = 0

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
            val firstSongId = firstBlock.songs.firstOrNull()?.let { parseQueueTitle(it).first }
            lastIntervenedSongId = firstSongId
            triggerInterventionForBlock(firstBlock, previousSong = null, currentSong = firstBlock.songs.firstOrNull())
        }
    }

    /**
     * Triggered when a new song transition occurs in the player.
     * Checks if this song represents the beginning of a new DJ block or frequency trigger.
     */
    fun onSongTransition(
        currentMediaItem: MediaItem?,
        reason: Int = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
    ) {
        if (!_isDjModeEnabled.value || currentMediaItem == null) {
            previousSongInfo = currentMediaItem?.toDjSongInfo()
            return
        }

        val currentRawId = parseQueueTitle(currentMediaItem).first
        Log.d(
            TAG,
            "onSongTransition: song=$currentRawId, reason=$reason, activeBlock=${_activeBlockIndex.value}, songsSinceIntervention=$songsSinceLastIntervention"
        )

        // Cancel previous pending speech generation or in-flight speech only when moving to a different song
        if (currentRawId != lastIntervenedSongId) {
            activeInterventionJob?.cancel()
            if (duckingCoordinator.isSpeaking.value) {
                duckingCoordinator.cancelSpeechAndRestore()
            }
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

        // 1. Find which block contains this song
        var targetBlockIndex = blocks.indexOfFirst { block ->
            block.songs.any { parseQueueTitle(it).first == currentRawId }
        }

        // 2. Fallback by player queue index if ID search fails
        if (targetBlockIndex == -1) {
            val player = playerProvider()
            val playerIndex = player?.currentMediaItemIndex ?: -1
            if (playerIndex >= 0 && clusterer.blockSize > 0) {
                val candidateIndex = playerIndex / clusterer.blockSize
                if (candidateIndex in blocks.indices) {
                    targetBlockIndex = candidateIndex
                }
            }
        }

        val isNewBlock = (targetBlockIndex != -1 && targetBlockIndex != _activeBlockIndex.value)
        songsSinceLastIntervention++
        val isPeriodicTrigger = (songsSinceLastIntervention >= 3)

        Log.d(
            TAG,
            "onSongTransition: targetBlockIndex=$targetBlockIndex, isNewBlock=$isNewBlock, isPeriodic=$isPeriodicTrigger (count=$songsSinceLastIntervention)"
        )

        // Trigger intervention on block transitions or periodic count (unless this exact song already had an intervention)
        if ((isNewBlock || isPeriodicTrigger) && currentRawId != lastIntervenedSongId) {
            val blockToAnnounce = if (targetBlockIndex in blocks.indices) {
                blocks[targetBlockIndex]
            } else if (_activeBlockIndex.value in blocks.indices) {
                blocks[_activeBlockIndex.value]
            } else {
                blocks.first()
            }

            if (targetBlockIndex != -1) {
                _activeBlockIndex.value = targetBlockIndex
            }
            songsSinceLastIntervention = 0
            lastIntervenedSongId = currentRawId

            val prevSongForScript = previousSongInfo

            // If user skipped (SEEK), debounce slightly (450ms) so rapid skipping doesn't fire multiple API calls
            val debounceMs = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) 450L else 0L

            activeInterventionJob = scope.launch {
                if (debounceMs > 0L) {
                    delay(debounceMs)
                }
                val songList = run {
                    val indexInBlock = blockToAnnounce.songs.indexOfFirst { parseQueueTitle(it).first == currentRawId }
                    if (indexInBlock >= 0) blockToAnnounce.songs.drop(indexInBlock) else blockToAnnounce.songs
                }
                val upcomingInfos = songList.map { it.toDjSongInfo() }
                executeIntervention(
                    theme = blockToAnnounce.title,
                    upcomingSongs = upcomingInfos,
                    previousSong = prevSongForScript
                )
            }
        }

        previousSongInfo = currentMediaItem.toDjSongInfo()
    }

    /**
     * Trigger an on-demand DJ intervention (e.g. from UI button).
     */
    fun triggerImmediateIntervention(currentSong: MediaItem?, upcomingSongs: List<MediaItem>) {
        activeInterventionJob?.cancel()
        if (duckingCoordinator.isSpeaking.value) {
            duckingCoordinator.cancelSpeechAndRestore()
        }
        activeInterventionJob = scope.launch {
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
    fun triggerInterventionForBlock(
        block: DjBlock,
        previousSong: DjSongInfo? = null,
        currentSong: MediaItem? = null
    ) {
        activeInterventionJob?.cancel()
        activeInterventionJob = scope.launch {
            val songList = if (currentSong != null) {
                val currentRawId = parseQueueTitle(currentSong).first
                val indexInBlock = block.songs.indexOfFirst { parseQueueTitle(it).first == currentRawId }
                if (indexInBlock >= 0) {
                    block.songs.drop(indexInBlock)
                } else {
                    block.songs
                }
            } else {
                block.songs
            }

            val upcomingInfos = songList.map { it.toDjSongInfo() }
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
        activeInterventionJob?.cancel()
        activeInterventionJob = scope.launch {
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
            if (!coroutineContext.isActive) return@withContext

            // 1. Generate Script
            val script = scriptGenerator.generateSpeech(
                upcomingSongs = upcomingSongs,
                blockTheme = theme,
                previousSong = previousSong
            )
            if (!coroutineContext.isActive) return@withContext

            _lastSpeechScript.value = script
            Log.i(TAG, "Generated DJ Script: $script")

            // 2. Synthesize Audio via Kokoro TTS (or null if weights missing)
            val audioFile = ttsEngine.synthesizeToWavFile(script)
            if (!coroutineContext.isActive) {
                audioFile?.delete()
                return@withContext
            }

            // 3. Duck audio and play speech (Kokoro WAV or native TTS fallback)
            withContext(Dispatchers.Main) {
                if (!coroutineContext.isActive) {
                    audioFile?.delete()
                    return@withContext
                }
                duckingCoordinator.playInterventionWithDucking(
                    audioFile = audioFile,
                    fallbackText = script
                ) {
                    Log.i(TAG, "DJ intervention completed successfully.")
                    audioFile?.let { try { it.delete() } catch (ignored: Exception) {} }
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "DJ intervention cancelled.")
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
