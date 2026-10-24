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
        const val MIN_SONGS_BETWEEN_INTERVENTIONS = 5
        const val MIN_PLAY_TIME_FOR_LISTEN_MS = 20_000L // 20s to be considered genuinely listened to
        const val PREFETCH_DELAY_MS = 15_000L // start prefetching 15s after song begins

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
    private var songsSinceIntervention: Int = 0

    // Pause state tracking
    private var isCurrentlyPaused: Boolean = false
    private var currentPausedSongId: String? = null

    // Background jobs and prefetch cache
    private var activeInterventionJob: Job? = null
    private var prefetchJob: Job? = null
    private var prefetchWatcherJob: Job? = null
    private var prefetchedIntervention: PrefetchedIntervention? = null

    fun isPlaybackActive(): Boolean {
        val player = playerProvider() ?: return false
        return player.playWhenReady &&
               player.playbackState != Player.STATE_IDLE &&
               player.playbackState != Player.STATE_ENDED
    }

    fun setDjModeEnabled(enabled: Boolean) {
        _isDjModeEnabled.value = enabled
        if (!enabled) {
            cancelAllActiveTasks()
            // Silence speech and restore volume immediately, without altering queue or deleting prefetch cache
            duckingCoordinator.cancelSpeechAndRestore()
        }
        Log.i(TAG, "DJ Mode set to: $enabled")
    }

    /**
     * Activates DJ Mode smoothly without restarting or replacing the current queue.
     * Uses any pre-warmed initial intervention if available, but only speaks if playback is rolling.
     */
    fun activateDjMode(controller: Player?) {
        setDjModeEnabled(true)
        val player = controller ?: playerProvider() ?: return

        // 1. Cluster current queue in memory if not already clustered
        val itemCount = player.mediaItemCount
        if (itemCount > 0 && _currentBlocks.value.isEmpty()) {
            val queue = mutableListOf<MediaItem>()
            for (i in 0 until itemCount) {
                queue.add(player.getMediaItemAt(i))
            }
            setupQueue(queue)
        }

        // 2. Identify current playing track
        val currentItem = player.currentMediaItem
        if (currentItem != null) {
            val currentRawId = parseQueueTitle(currentItem).first
            if (currentPlayingSongId == null) {
                currentPlayingSongId = currentRawId
                currentSongStartTimeMs = System.currentTimeMillis()
                currentPlayingSongInfo = currentItem.toDjSongInfo()
            }

            // CRITICAL: Only speak if music playback is active. If paused/idle, keep in background!
            if (isPlaybackActive()) {
                val cached = prefetchedIntervention
                if (cached != null && (cached.targetSongId == currentRawId || cached.targetSongId == "initial")) {
                    Log.i(TAG, "HIT PRE-WARMED INTERVENTION on DJ activation for $currentRawId! Starting instant speech.")
                    prefetchedIntervention = null
                    lastIntervenedSongId = currentRawId
                    songsSinceIntervention = 0
                    _lastSpeechScript.value = cached.script

                    duckingCoordinator.prepareForIntervention()
                    duckingCoordinator.playInterventionWithDucking(
                        audioFile = cached.audioFile,
                        fallbackText = cached.script
                    ) {
                        cached.audioFile?.let { try { it.delete() } catch (_: Exception) {} }
                    }
                } else if (lastIntervenedSongId == null) {
                    val upcomingInfos = listOf(currentItem.toDjSongInfo())
                    val theme = resolveThemeForSong(currentItem)
                    lastIntervenedSongId = currentRawId
                    songsSinceIntervention = 0
                    activeInterventionJob = scope.launch {
                        executeIntervention(
                            theme = theme,
                            upcomingSongs = upcomingInfos,
                            previousSong = null,
                            wasPreviousSongListened = false
                        )
                    }
                }
                schedulePrefetchWatcher(currentRawId)
            } else {
                Log.d(TAG, "activateDjMode: player is paused/idle. Keeping pre-warmed intervention in background.")
            }
        }
    }

    /**
     * Pre-warms the first DJ intervention in the background as soon as the app opens.
     * Generates and synthesizes the audio, but DOES NOT play it unless DJ mode is activated.
     */
    fun prewarmInitialIntervention() {
        if (prefetchedIntervention != null || prefetchJob?.isActive == true) {
            return
        }

        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                // Wait briefly for service and player to settle
                delay(1200L)
                val targetSong = withContext(Dispatchers.Main) {
                    val player = playerProvider() ?: return@withContext null
                    try {
                        player.currentMediaItem ?: if (player.mediaItemCount > 0) player.getMediaItemAt(0) else null
                    } catch (e: Exception) {
                        null
                    }
                }

                val songInfo = targetSong?.toDjSongInfo()
                    ?: DjSongInfo(mediaId = "initial", title = "tu selección musical", artist = "tus artistas favoritos", album = null, genre = null, year = null)
                val rawId = targetSong?.let { parseQueueTitle(it).first } ?: "initial"
                val theme = if (targetSong != null) resolveThemeForSong(targetSong) else "buena música"

                Log.d(TAG, "Pre-warming initial DJ intervention in background for: '${songInfo.title}' by ${songInfo.artist}")
                val script = scriptGenerator.generateSpeech(
                    upcomingSongs = listOf(songInfo),
                    blockTheme = theme,
                    previousSong = null,
                    wasPreviousSongListened = false
                )

                if (!isActive) return@launch

                val audioFile = ttsEngine.synthesizeToWavFile(script)
                if (!isActive) {
                    audioFile?.delete()
                    return@launch
                }

                prefetchedIntervention = PrefetchedIntervention(
                    targetSongId = rawId,
                    script = script,
                    audioFile = audioFile,
                    theme = theme
                )
                Log.i(TAG, "Initial DJ intervention successfully pre-warmed in background! Ready for instant activation.")
            } catch (e: CancellationException) {
                Log.d(TAG, "Pre-warm job cancelled.")
            } catch (e: Exception) {
                Log.w(TAG, "Pre-warm generation encountered non-fatal error", e)
            }
        }
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

            // If playback started and this track hasn't had an intervention yet, use pre-warmed speech over the intro!
            val player = playerProvider()
            val currentItem = player?.currentMediaItem
            if (currentItem != null && lastIntervenedSongId == null) {
                val currentRawId = parseQueueTitle(currentItem).first
                currentPlayingSongId = currentRawId
                currentSongStartTimeMs = System.currentTimeMillis()
                currentPlayingSongInfo = currentItem.toDjSongInfo()

                val cached = prefetchedIntervention
                if (cached != null && (cached.targetSongId == currentRawId || cached.targetSongId == "initial")) {
                    Log.i(TAG, "HIT PRE-WARMED CACHE on Play pressed! Starting instant DJ speech.")
                    prefetchedIntervention = null
                    lastIntervenedSongId = currentRawId
                    songsSinceIntervention = 0
                    _lastSpeechScript.value = cached.script

                    duckingCoordinator.prepareForIntervention()
                    duckingCoordinator.playInterventionWithDucking(
                        audioFile = cached.audioFile,
                        fallbackText = cached.script
                    ) {
                        cached.audioFile?.let { try { it.delete() } catch (_: Exception) {} }
                    }
                }
                schedulePrefetchWatcher(currentRawId)
            }
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
        songsSinceIntervention = 0
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
            "onSongTransition: song=$currentRawId, reason=$reason, songsSinceIntervention=$songsSinceIntervention"
        )

        // Evaluate previous song listen duration (did user listen or rapid-skip?)
        if (currentPlayingSongId != null) {
            val elapsedMs = now - currentSongStartTimeMs
            // Count every song transition towards the 5-song threshold (including skips)
            songsSinceIntervention++

            if (elapsedMs >= MIN_PLAY_TIME_FOR_LISTEN_MS) {
                lastGenuineListenedSong = currentPlayingSongInfo
                Log.d(TAG, "Previous song was listened ($elapsedMs ms). Song count: $songsSinceIntervention")
            } else {
                Log.d(TAG, "Previous song was skipped ($elapsedMs ms). Incremented song count: $songsSinceIntervention, but skipping previous song context.")
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

        val isPlaying = isPlaybackActive()
        isCurrentlyPaused = !isPlaying

        // CRITICAL: If playback is not active (e.g. app opened and queue restored while paused),
        // DO NOT intervene! Keep prewarm in background until user presses play.
        if (!isPlaying) {
            Log.d(TAG, "onSongTransition: player is not playing (paused/idle). Keeping prewarm in background.")
            return
        }

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
        val isFrequencyTrigger = (songsSinceIntervention >= MIN_SONGS_BETWEEN_INTERVENTIONS)
        val isFirstSongSession = (lastIntervenedSongId == null)

        Log.d(
            TAG,
            "onSongTransition: targetBlockIndex=$targetBlockIndex, isNewBlock=$isNewBlock, hasMatchingPrefetch=$hasMatchingPrefetch, isFrequencyTrigger=$isFrequencyTrigger, isFirstSongSession=$isFirstSongSession (songsSinceIntervention=$songsSinceIntervention)"
        )

        // Keep active block updated even if not intervening
        if (targetBlockIndex != -1) {
            _activeBlockIndex.value = targetBlockIndex
        }

        // Intervene on session start, when prefetch is cached, or when 5 songs threshold is reached
        val shouldIntervene = (isFirstSongSession || hasMatchingPrefetch || isFrequencyTrigger || (isNewBlock && isFrequencyTrigger))

        if (shouldIntervene && currentRawId != lastIntervenedSongId) {

            if (cached != null && cached.targetSongId == currentRawId) {
                // INSTANT PLAYBACK FROM PREFETCH CACHE (0 ms latency)
                Log.i(TAG, "HIT PREFETCH CACHE for song $currentRawId! Starting instant DJ speech.")
                prefetchedIntervention = null
                lastIntervenedSongId = currentRawId
                songsSinceIntervention = 0
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
            songsSinceIntervention = 0

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
                        val isFreq = (songsSinceIntervention + 1 >= MIN_SONGS_BETWEEN_INTERVENTIONS)
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
