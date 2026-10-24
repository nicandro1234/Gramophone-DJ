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
        const val MIN_SONGS_BETWEEN_INTERVENTIONS = 3
        const val MIN_PLAY_TIME_FOR_LISTEN_MS = 20_000L // 20s to be considered genuinely listened to
        const val PREFETCH_DELAY_MS = 6_000L // start prefetching 6s after song begins

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

    private data class PrefetchedIntervention(
        val targetSongId: String,
        val script: String,
        val audioFile: File?,
        val theme: String
    )

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

    // Listening and skip state tracking
    private var lastIntervenedSongId: String? = null
    private var currentPlayingSongId: String? = null
    private var currentSongStartTimeMs: Long = 0L
    private var currentPlayingSongInfo: DjSongInfo? = null
    private var lastGenuineListenedSong: DjSongInfo? = null
    private var fullyListenedSongsSinceIntervention: Int = 0

    // Pause state tracking
    private var isCurrentlyPaused: Boolean = false
    private var currentPausedSongId: String? = null

    // Background jobs and prefetch cache
    private var activeInterventionJob: Job? = null
    private var prefetchJob: Job? = null
    private var prefetchWatcherJob: Job? = null
    private var prefetchedIntervention: PrefetchedIntervention? = null

    fun setDjModeEnabled(enabled: Boolean) {
        _isDjModeEnabled.value = enabled
        if (!enabled) {
            cancelAllActiveTasks()
            clearPrefetch()
            duckingCoordinator.cancelSpeechAndRestore()
        }
        Log.i(TAG, "DJ Mode set to: $enabled")
    }

    /**
     * Called whenever playback state changes (explicit user play / pause).
     */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!_isDjModeEnabled.value) return

        if (!playWhenReady) {
            // User paused playback: pause speech immediately
            isCurrentlyPaused = true
            currentPausedSongId = currentPlayingSongId
            prefetchWatcherJob?.cancel()
            if (duckingCoordinator.isSpeaking.value) {
                duckingCoordinator.onPlaybackPaused()
            }
            Log.d(TAG, "onPlayWhenReadyChanged: paused on $currentPausedSongId. Speech paused.")
        } else {
            // User resumed playback
            if (isCurrentlyPaused && currentPlayingSongId == currentPausedSongId && currentPlayingSongId != null) {
                isCurrentlyPaused = false
                currentPausedSongId = null
                Log.d(TAG, "onPlayWhenReadyChanged: resumed same song ($currentPlayingSongId).")
                currentPlayingSongId?.let { schedulePrefetchWatcher(it) }
                return
            }
            isCurrentlyPaused = false
            currentPausedSongId = null
        }
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
        fullyListenedSongsSinceIntervention = 0
        Log.i(TAG, "Generated ${blocks.size} DJ blocks from ${songs.size} tracks")
    }

    /**
     * Start a DJ playback session immediately:
     * - Cluster songs, populate queue, begin playback, and trigger first block intro speech.
     */
    fun startDjSession(controller: Player?, songs: List<MediaItem>) {
        setDjModeEnabled(true)
        if (songs.isEmpty()) {
            Log.w(TAG, "No songs available to start DJ session.")
            return
        }

        cancelAllActiveTasks()
        clearPrefetch()
        fullyListenedSongsSinceIntervention = 0
        lastGenuineListenedSong = null
        lastIntervenedSongId = null

        // 1. Pre-duck volume immediately so playback starts attenuated
        duckingCoordinator.prepareForIntervention()

        // 2. Generate a fresh cluster of blocks
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
            val firstSong = firstBlock.songs.firstOrNull()
            val firstSongId = firstSong?.let { parseQueueTitle(it).first }
            lastIntervenedSongId = firstSongId
            currentPlayingSongId = firstSongId
            currentSongStartTimeMs = System.currentTimeMillis()
            currentPlayingSongInfo = firstSong?.toDjSongInfo()

            triggerInterventionForBlock(
                block = firstBlock,
                previousSong = null,
                currentSong = firstSong,
                wasPreviousSongListened = false
            )

            // Start prefetch watcher for the first song
            firstSongId?.let { schedulePrefetchWatcher(it) }
        }
    }

    /**
     * Triggered when a new song transition occurs in the player.
     * Evaluates listen time of previous song, checks prefetch cache, and coordinates frequency.
     */
    fun onSongTransition(
        currentMediaItem: MediaItem?,
        reason: Int = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
    ) {
        if (!_isDjModeEnabled.value || currentMediaItem == null) {
            currentPlayingSongInfo = currentMediaItem?.toDjSongInfo()
            return
        }

        val currentRawId = parseQueueTitle(currentMediaItem).first
        val now = System.currentTimeMillis()
        Log.d(
            TAG,
            "onSongTransition: song=$currentRawId, reason=$reason, songsSinceIntervention=$fullyListenedSongsSinceIntervention"
        )

        // Evaluate previous song listen duration (did user listen or rapid-skip?)
        if (currentPlayingSongId != null) {
            val elapsedMs = now - currentSongStartTimeMs
            if (elapsedMs >= MIN_PLAY_TIME_FOR_LISTEN_MS) {
                fullyListenedSongsSinceIntervention++
                lastGenuineListenedSong = currentPlayingSongInfo
                Log.d(TAG, "Previous song was listened ($elapsedMs ms). Listened count: $fullyListenedSongsSinceIntervention")
            } else {
                Log.d(TAG, "Previous song was skipped quickly ($elapsedMs ms). Skipping listen count increment and previous song context.")
                // Discard in-flight prefetch if tied to a different upcoming track
                if (prefetchedIntervention?.targetSongId != currentRawId) {
                    prefetchJob?.cancel()
                    clearPrefetch()
                }
            }
        }

        // Cancel previous pending speech generation or in-flight speech when moving to another song
        if (currentRawId != lastIntervenedSongId) {
            activeInterventionJob?.cancel()
            if (duckingCoordinator.isSpeaking.value) {
                duckingCoordinator.cancelSpeechAndRestore()
            }
        }

        // Update current playback trackers
        currentPlayingSongId = currentRawId
        currentSongStartTimeMs = now
        currentPlayingSongInfo = currentMediaItem.toDjSongInfo()
        isCurrentlyPaused = false
        currentPausedSongId = null

        // Ensure blocks exist
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

        var targetBlockIndex = blocks.indexOfFirst { block ->
            block.songs.any { parseQueueTitle(it).first == currentRawId }
        }
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
        val cached = prefetchedIntervention
        val hasMatchingPrefetch = (cached != null && cached.targetSongId == currentRawId)
        val isFrequencyTrigger = (fullyListenedSongsSinceIntervention >= MIN_SONGS_BETWEEN_INTERVENTIONS)
        val isFirstSongSession = (lastIntervenedSongId == null)

        Log.d(
            TAG,
            "onSongTransition: targetBlockIndex=$targetBlockIndex, isNewBlock=$isNewBlock, hasMatchingPrefetch=$hasMatchingPrefetch, isFrequencyTrigger=$isFrequencyTrigger, isFirstSongSession=$isFirstSongSession (listenedCount=$fullyListenedSongsSinceIntervention)"
        )

        val shouldIntervene = (isNewBlock || hasMatchingPrefetch || isFrequencyTrigger || isFirstSongSession)

        if (shouldIntervene && currentRawId != lastIntervenedSongId) {
            if (targetBlockIndex != -1) {
                _activeBlockIndex.value = targetBlockIndex
            }

            if (cached != null && cached.targetSongId == currentRawId) {
                // INSTANT PLAYBACK FROM PREFETCH CACHE (0 ms latency)
                Log.i(TAG, "HIT PREFETCH CACHE for song $currentRawId! Starting instant DJ speech.")
                prefetchedIntervention = null
                lastIntervenedSongId = currentRawId
                fullyListenedSongsSinceIntervention = 0
                _lastSpeechScript.value = cached.script

                duckingCoordinator.prepareForIntervention()
                duckingCoordinator.playInterventionWithDucking(
                    audioFile = cached.audioFile,
                    fallbackText = cached.script
                ) {
                    Log.i(TAG, "DJ cached intervention completed successfully.")
                    cached.audioFile?.let { try { it.delete() } catch (_: Exception) {} }
                }

                schedulePrefetchWatcher(currentRawId)
                return
            }

            // Fallback: no prefetch available, generate reactively with debounce
            val theme = resolveThemeForSong(currentMediaItem)
            val prevSongForScript = lastGenuineListenedSong
            val wasListened = (prevSongForScript != null)

            lastIntervenedSongId = currentRawId
            fullyListenedSongsSinceIntervention = 0

            val debounceMs = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) 250L else 0L

            activeInterventionJob = scope.launch {
                if (debounceMs > 0L) {
                    delay(debounceMs)
                }
                if (!isActive || currentPlayingSongId != currentRawId || isCurrentlyPaused) return@launch

                val upcomingInfos = listOf(currentMediaItem.toDjSongInfo())
                executeIntervention(
                    theme = theme,
                    upcomingSongs = upcomingInfos,
                    previousSong = prevSongForScript,
                    wasPreviousSongListened = wasListened
                )
            }
        }

        // Schedule prefetch for upcoming songs while this track plays
        schedulePrefetchWatcher(currentRawId)
    }

    /**
     * Schedules a background task to pre-generate speech for the upcoming song
     * once the current song has been playing smoothly for PREFETCH_DELAY_MS.
     */
    private fun schedulePrefetchWatcher(forSongId: String) {
        prefetchWatcherJob?.cancel()
        prefetchWatcherJob = scope.launch {
            delay(PREFETCH_DELAY_MS)
            if (!isActive || currentPlayingSongId != forSongId || isCurrentlyPaused) return@launch

            val (nextItem, isNextNewBlock, isNextFrequency) = withContext(Dispatchers.Main) {
                try {
                    val player = playerProvider() ?: return@withContext Triple(null, false, false)
                    val nextIndex = player.currentMediaItemIndex + 1
                    if (nextIndex in 0 until player.mediaItemCount) {
                        val item = player.getMediaItemAt(nextIndex)
                        val nextRawId = parseQueueTitle(item).first
                        val blocks = _currentBlocks.value
                        val nextBlockIndex = blocks.indexOfFirst { b -> b.songs.any { parseQueueTitle(it).first == nextRawId } }
                        val isNewBlock = (nextBlockIndex != -1 && nextBlockIndex != _activeBlockIndex.value)
                        val isFreq = (fullyListenedSongsSinceIntervention + 1 >= MIN_SONGS_BETWEEN_INTERVENTIONS)
                        Triple(item, isNewBlock, isFreq)
                    } else {
                        Triple(null, false, false)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking upcoming track on main thread", e)
                    Triple(null, false, false)
                }
            }

            if (!isActive || currentPlayingSongId != forSongId || isCurrentlyPaused) return@launch

            if (nextItem != null && (isNextNewBlock || isNextFrequency)) {
                val nextRawId = parseQueueTitle(nextItem).first
                if (prefetchedIntervention?.targetSongId != nextRawId) {
                    Log.i(TAG, "Prefetch watcher: initiating prefetch for upcoming track: $nextRawId (newBlock=$isNextNewBlock, freq=$isNextFrequency)")
                    triggerPrefetchForSong(nextItem)
                }
            }
        }
    }

    /**
     * Generates script and synthesizes audio in background ahead of time for target upcoming song.
     */
    private fun triggerPrefetchForSong(targetSong: MediaItem) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                val targetRawId = parseQueueTitle(targetSong).first
                val targetInfo = targetSong.toDjSongInfo()
                val theme = resolveThemeForSong(targetSong)
                val prevSong = lastGenuineListenedSong

                Log.d(TAG, "Prefetching DJ speech for upcoming track: '${targetInfo.title}' by ${targetInfo.artist}")
                val script = scriptGenerator.generateSpeech(
                    upcomingSongs = listOf(targetInfo),
                    blockTheme = theme,
                    previousSong = prevSong,
                    wasPreviousSongListened = (prevSong != null)
                )
                if (!isActive) return@launch

                val audioFile = ttsEngine.synthesizeToWavFile(script)
                if (!isActive) {
                    audioFile?.delete()
                    return@launch
                }

                clearPrefetch()
                prefetchedIntervention = PrefetchedIntervention(
                    targetSongId = targetRawId,
                    script = script,
                    audioFile = audioFile,
                    theme = theme
                )
                Log.i(TAG, "Prefetch successfully cached for $targetRawId! Ready for instant playback.")
            } catch (e: CancellationException) {
                Log.d(TAG, "Prefetch job cancelled.")
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch generation failed", e)
            }
        }
    }

    private fun clearPrefetch() {
        prefetchedIntervention?.audioFile?.let {
            try { it.delete() } catch (_: Exception) {}
        }
        prefetchedIntervention = null
    }

    private fun cancelAllActiveTasks() {
        prefetchWatcherJob?.cancel()
        prefetchJob?.cancel()
        activeInterventionJob?.cancel()
        prefetchWatcherJob = null
        prefetchJob = null
        activeInterventionJob = null
    }

    private fun resolveThemeForSong(song: MediaItem): String {
        val info = song.toDjSongInfo()
        val genre = info.genre?.takeIf { it.isNotBlank() }
        val artist = info.artist.takeIf { it.isNotBlank() }

        val block = _currentBlocks.value.find { b ->
            b.songs.any { parseQueueTitle(it).first == parseQueueTitle(song).first }
        }
        return block?.title ?: genre?.let { "un bloque de $it" } ?: artist?.let { "lo mejor de $it" } ?: "buena música"
    }

    /**
     * Trigger an on-demand DJ intervention (e.g. from UI button).
     */
    fun triggerImmediateIntervention(currentSong: MediaItem?, upcomingSongs: List<MediaItem>) {
        cancelAllActiveTasks()
        clearPrefetch()
        if (duckingCoordinator.isSpeaking.value) {
            duckingCoordinator.cancelSpeechAndRestore()
        }
        activeInterventionJob = scope.launch {
            val songInfoList = upcomingSongs.take(3).map { it.toDjSongInfo() }
            val theme = if (currentSong != null) resolveThemeForSong(currentSong) else "nuestra selección musical"
            executeIntervention(
                theme = theme,
                upcomingSongs = songInfoList,
                previousSong = lastGenuineListenedSong,
                wasPreviousSongListened = (lastGenuineListenedSong != null)
            )
        }
    }

    /**
     * Public method to trigger speech for a specific block.
     */
    fun triggerInterventionForBlock(
        block: DjBlock,
        previousSong: DjSongInfo? = null,
        currentSong: MediaItem? = null,
        wasPreviousSongListened: Boolean = true
    ) {
        cancelAllActiveTasks()
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
                previousSong = previousSong,
                wasPreviousSongListened = wasPreviousSongListened
            )
        }
    }

    /**
     * Test Livi's voice directly with speech ducking.
     */
    fun testVoice(sampleText: String = "Hola, soy Livi, tu DJ personal. Todo listo para disfrutar de buena música.") {
        cancelAllActiveTasks()
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
                    audioFile?.let { try { it.delete() } catch (_: Exception) {} }
                }
            }
        }
    }

    private suspend fun executeIntervention(
        theme: String,
        upcomingSongs: List<DjSongInfo>,
        previousSong: DjSongInfo?,
        wasPreviousSongListened: Boolean = true
    ) = withContext(Dispatchers.IO) {
        try {
            if (!coroutineContext.isActive || isCurrentlyPaused) return@withContext

            // 1. Generate Script
            val script = scriptGenerator.generateSpeech(
                upcomingSongs = upcomingSongs,
                blockTheme = theme,
                previousSong = previousSong,
                wasPreviousSongListened = wasPreviousSongListened
            )
            if (!coroutineContext.isActive || isCurrentlyPaused) return@withContext

            _lastSpeechScript.value = script
            Log.i(TAG, "Generated DJ Script: $script")

            // 2. Synthesize Audio via Kokoro TTS (or null if weights missing)
            val audioFile = ttsEngine.synthesizeToWavFile(script)
            if (!coroutineContext.isActive || isCurrentlyPaused) {
                audioFile?.delete()
                return@withContext
            }

            // 3. Duck audio and play speech (Kokoro WAV or native TTS fallback)
            withContext(Dispatchers.Main) {
                if (!coroutineContext.isActive || isCurrentlyPaused) {
                    audioFile?.delete()
                    return@withContext
                }
                duckingCoordinator.playInterventionWithDucking(
                    audioFile = audioFile,
                    fallbackText = script
                ) {
                    Log.i(TAG, "DJ intervention completed successfully.")
                    audioFile?.let { try { it.delete() } catch (_: Exception) {} }
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "DJ intervention cancelled.")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing DJ intervention", e)
        }
    }

    fun release() {
        cancelAllActiveTasks()
        clearPrefetch()
        duckingCoordinator.release()
        ttsEngine.release()
        instance = null
    }
}
