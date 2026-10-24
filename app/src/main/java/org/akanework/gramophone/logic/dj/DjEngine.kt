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
                    val count = player.mediaItemCount
                    val cur = player.currentMediaItemIndex
                    val end = (cur + 4).coerceAtMost(count - 1)
                    val upcomingInfos = if (count > 0) {
                        (cur..end).map { player.getMediaItemAt(it).toDjSongInfo() }
                    } else listOf(currentItem.toDjSongInfo())
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
                prepareNextIntervention()
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
                val (targetSong, upcomingSlice) = withContext(Dispatchers.Main) {
                    val player = playerProvider() ?: return@withContext Pair(null, emptyList<MediaItem>())
                    try {
                        val count = player.mediaItemCount
                        if (count > 0) {
                            val cur = player.currentMediaItemIndex.coerceIn(0, count - 1)
                            val target = player.getMediaItemAt(cur)
                            val end = (cur + 4).coerceAtMost(count - 1)
                            val slice = (cur..end).map { player.getMediaItemAt(it) }
                            Pair(target, slice)
                        } else {
                            Pair(null, emptyList())
                        }
                    } catch (e: Exception) {
                        Pair(null, emptyList())
                    }
                }

                val targetInfos = if (upcomingSlice.isNotEmpty()) {
                    upcomingSlice.map { it.toDjSongInfo() }
                } else if (targetSong != null) {
                    listOf(targetSong.toDjSongInfo())
                } else {
                    listOf(DjSongInfo(mediaId = "initial", title = "tu selección musical", artist = "tus artistas favoritos", album = null, genre = null, year = null))
                }
                val rawId = targetSong?.let { parseQueueTitle(it).first } ?: "initial"
                val theme = if (targetSong != null) resolveThemeForSong(targetSong) else "buena música de tu biblioteca"

                Log.d(TAG, "Pre-warming initial DJ intervention in background for: '${targetInfos.first().title}' by ${targetInfos.first().artist} (theme='$theme')")
                val script = scriptGenerator.generateSpeech(
                    upcomingSongs = targetInfos,
                    blockTheme = theme,
                    previousSong = null,
                    wasPreviousSongListened = false,
                    timeoutMs = 15000L
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
            if (duckingCoordinator.isSpeaking.value) {
                duckingCoordinator.onPlaybackPaused()
            }
            Log.d(TAG, "onPlayWhenReadyChanged: paused on $currentPausedSongId. Speech paused.")
        } else {
            // User resumed playback
            val isSameSongResume = (isCurrentlyPaused && currentPlayingSongId != null && currentPlayingSongId == currentPausedSongId && lastIntervenedSongId == currentPlayingSongId)
            isCurrentlyPaused = false
            currentPausedSongId = null

            if (isSameSongResume) {
                Log.d(TAG, "onPlayWhenReadyChanged: resumed same song ($currentPlayingSongId). Already intervened.")
                return
            }

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
                } else {
                    val count = player?.mediaItemCount ?: 0
                    val cur = player?.currentMediaItemIndex ?: 0
                    val end = (cur + 4).coerceAtMost(count - 1)
                    val upcomingInfos = if (count > 0) {
                        (cur..end).mapNotNull { player?.getMediaItemAt(it)?.toDjSongInfo() }
                    } else listOf(currentItem.toDjSongInfo())
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
                prepareNextIntervention()
            }
        }
    }

    private fun getActiveMood(): String {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString("dj_mood", "classic") ?: "classic"
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
        val blocks = clusterer.clusterTracks(songs, mood = getActiveMood())
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
        songsSinceIntervention = 0
        lastGenuineListenedSong = null
        lastIntervenedSongId = null

        // 1. Generate a fresh cluster of blocks
        val blocks = clusterer.clusterTracks(songs, mood = getActiveMood())
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

        val firstBlock = blocks.firstOrNull()
        val firstSong = firstBlock?.songs?.firstOrNull()
        val firstSongId = firstSong?.let { parseQueueTitle(it).first }
        currentPlayingSongId = firstSongId
        currentSongStartTimeMs = System.currentTimeMillis()
        currentPlayingSongInfo = firstSong?.toDjSongInfo()

        val cached = prefetchedIntervention
        if (cached != null) {
            Log.i(TAG, "startDjSession: Instant 0ms speech with pre-warmed initial cache!")
            prefetchedIntervention = null
            lastIntervenedSongId = firstSongId
            songsSinceIntervention = 0
            _lastSpeechScript.value = cached.script

            duckingCoordinator.prepareForIntervention()
            duckingCoordinator.playInterventionWithDucking(
                audioFile = cached.audioFile,
                fallbackText = cached.script
            ) {
                Log.i(TAG, "DJ initial cached speech completed.")
                cached.audioFile?.let { try { it.delete() } catch (_: Exception) {} }
            }
            prepareNextIntervention()
        } else {
            firstBlock?.let { block ->
                activeInterventionJob = scope.launch {
                    val firstInfos = block.songs.take(5).map { it.toDjSongInfo() }.ifEmpty {
                        listOf(DjSongInfo("initial", "tu música", "tus artistas", null, null, null))
                    }
                    val theme = if (firstSong != null) resolveThemeForSong(firstSong) else block.title
                    val success = executeIntervention(
                        theme = theme,
                        upcomingSongs = firstInfos,
                        previousSong = null,
                        wasPreviousSongListened = false
                    )
                    if (success) {
                        lastIntervenedSongId = firstSongId
                        songsSinceIntervention = 0
                    }
                    prepareNextIntervention()
                }
            }
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
        // Crucial: Only increment when transitioning FROM a previous song TO a DIFFERENT song!
        if (currentPlayingSongId != null && currentPlayingSongId != currentRawId) {
            val elapsedMs = now - currentSongStartTimeMs
            // Count every song transition towards the 5-song threshold (including skips)
            songsSinceIntervention++

            if (elapsedMs >= MIN_PLAY_TIME_FOR_LISTEN_MS) {
                lastGenuineListenedSong = currentPlayingSongInfo
                Log.d(TAG, "Previous song was listened ($elapsedMs ms). Song count: $songsSinceIntervention")
            } else {
                Log.d(TAG, "Previous song was skipped ($elapsedMs ms). Incremented song count: $songsSinceIntervention, keeping pipeline alive.")
            }
        }

        // Cancel previous pending speech playback if user skipped to another song
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

        val player = playerProvider()
        val isColdStart = (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && player?.isPlaying != true && player?.playWhenReady != true)
        if (isColdStart) {
            Log.d(TAG, "onSongTransition: ignoring cold start playlist restore while not playing.")
            return
        }

        // Ensure blocks exist
        if (_currentBlocks.value.isEmpty()) {
            playerProvider()?.let { p ->
                val count = p.mediaItemCount
                val queue = mutableListOf<MediaItem>()
                for (i in 0 until count) {
                    queue.add(p.getMediaItemAt(i))
                }
                if (queue.isNotEmpty()) {
                    setupQueue(queue)
                }
            }
        }

        val isFirstSongSession = (lastIntervenedSongId == null)
        val cached = prefetchedIntervention
        val hasMatchingCached = (cached != null && (cached.targetSongId == currentRawId || cached.targetSongId == "initial"))
        val isBlockBoundary = (player != null && player.currentMediaItemIndex > 0 && player.currentMediaItemIndex % MIN_SONGS_BETWEEN_INTERVENTIONS == 0)
        val isFrequencyTrigger = (songsSinceIntervention >= MIN_SONGS_BETWEEN_INTERVENTIONS)

        Log.d(
            TAG,
            "onSongTransition: song=$currentRawId (index=${player?.currentMediaItemIndex}), songsSinceIntervention=$songsSinceIntervention, isFrequencyTrigger=$isFrequencyTrigger, hasMatchingCached=$hasMatchingCached, isBlockBoundary=$isBlockBoundary, cachedTarget=${cached?.targetSongId}"
        )

        val shouldTrigger = (isFirstSongSession || hasMatchingCached || (isFrequencyTrigger && isBlockBoundary)) && currentRawId != lastIntervenedSongId

        if (shouldTrigger) {
            if (hasMatchingCached && cached != null) {
                // INSTANT PLAYBACK FROM CONTINUOUS PIPELINE CACHE (0 ms latency)
                Log.i(TAG, "Continuous Pipeline: PLAYING cached speech for song $currentRawId! songsCount=$songsSinceIntervention")
                prefetchedIntervention = null
                lastIntervenedSongId = currentRawId
                songsSinceIntervention = 0
                _lastSpeechScript.value = cached.script

                duckingCoordinator.prepareForIntervention()
                duckingCoordinator.playInterventionWithDucking(
                    audioFile = cached.audioFile,
                    fallbackText = cached.script
                ) {
                    Log.i(TAG, "DJ intervention completed successfully.")
                    cached.audioFile?.let { try { it.delete() } catch (_: Exception) {} }
                }

                // Immediately start pre-loading the next speech in background for the next block!
                prepareNextIntervention()
                return
            } else if (isFirstSongSession || isBlockBoundary || isFrequencyTrigger) {
                // Stale or missing cache: clear prefetch if it belonged to a skipped-over song
                if (cached != null && cached.targetSongId != currentRawId) {
                    Log.d(TAG, "Cached speech was for ${cached.targetSongId}, but current song is $currentRawId. Clearing stale cache.")
                    clearPrefetch()
                }

                activeInterventionJob = scope.launch {
                    val count = player?.mediaItemCount ?: 0
                    val cur = player?.currentMediaItemIndex ?: 0
                    val end = (cur + 4).coerceAtMost(count - 1)
                    val upcomingInfos = if (count > 0) {
                        (cur..end).mapNotNull { player?.getMediaItemAt(it)?.toDjSongInfo() }
                    } else listOf(currentMediaItem.toDjSongInfo())
                    val theme = resolveThemeForSong(currentMediaItem)

                    val success = executeIntervention(
                        theme = theme,
                        upcomingSongs = upcomingInfos,
                        previousSong = lastGenuineListenedSong,
                        wasPreviousSongListened = (lastGenuineListenedSong != null)
                    )
                    if (success) {
                        lastIntervenedSongId = currentRawId
                        songsSinceIntervention = 0
                    }
                    prepareNextIntervention()
                }
                return
            }
        }
    }

    /**
     * Continuous Pipeline: Pre-loads the NEXT speech intervention in the background.
     * Starts immediately after any speech plays, giving the LLM all the time while the user
     * listens to or skips through the intervening songs.
     */
    fun prepareNextIntervention() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                delay(800L)
                if (!isActive) return@launch

                val (targetSong, upcomingSlice) = withContext(Dispatchers.Main) {
                    val player = playerProvider() ?: return@withContext Pair(null, emptyList<MediaItem>())
                    val count = player.mediaItemCount
                    if (count == 0) return@withContext Pair(null, emptyList<MediaItem>())
                    val curIndex = player.currentMediaItemIndex
                    // Target is ALWAYS the start of the next 5-song block!
                    val nextBlockStartIndex = ((curIndex / MIN_SONGS_BETWEEN_INTERVENTIONS) + 1) * MIN_SONGS_BETWEEN_INTERVENTIONS
                    if (nextBlockStartIndex >= count) return@withContext Pair(null, emptyList<MediaItem>())

                    val target = player.getMediaItemAt(nextBlockStartIndex)
                    val endIndex = (nextBlockStartIndex + 4).coerceAtMost(count - 1)
                    val slice = (nextBlockStartIndex..endIndex).map { player.getMediaItemAt(it) }
                    Pair(target, slice)
                }
                if (targetSong == null || upcomingSlice.isEmpty()) return@launch

                val targetRawId = parseQueueTitle(targetSong).first
                val targetInfos = upcomingSlice.map { it.toDjSongInfo() }
                val theme = resolveThemeForSong(targetSong)

                Log.d(TAG, "Continuous Pipeline: Loading NEXT speech for mix starting with '${targetInfos.first().title}' (targetSongId=$targetRawId, ${targetInfos.size} songs in block, theme='$theme')")
                val script = scriptGenerator.generateSpeech(
                    upcomingSongs = targetInfos,
                    blockTheme = theme,
                    previousSong = lastGenuineListenedSong,
                    wasPreviousSongListened = (lastGenuineListenedSong != null),
                    timeoutMs = 20000L
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
                Log.i(TAG, "Continuous Pipeline: Next speech successfully cached for $targetRawId ('${targetInfos.first().title}')! Ready for instant playback.")
            } catch (e: CancellationException) {
                Log.d(TAG, "Pipeline preparation cancelled.")
            } catch (e: Exception) {
                Log.w(TAG, "Pipeline preparation failed", e)
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
        prefetchJob?.cancel()
        activeInterventionJob?.cancel()
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
        val blockDesc = if (block != null) {
            val desc = block.themeDescription.takeIf { !it.isNullOrBlank() }
            if (desc != null) "${block.title}: $desc" else block.title
        } else null
        return blockDesc ?: genre?.let { "un bloque de $it" } ?: artist?.let { "lo mejor de $it" } ?: "buena música de tu biblioteca"
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

        val cached = prefetchedIntervention
        val firstRawId = currentSong?.let { parseQueueTitle(it).first }
        if (cached != null && (firstRawId == null || cached.targetSongId == firstRawId || cached.targetSongId == "initial")) {
            Log.i(TAG, "HIT PRE-WARMED CACHE in triggerInterventionForBlock! Starting instant DJ speech.")
            prefetchedIntervention = null
            _lastSpeechScript.value = cached.script
            duckingCoordinator.prepareForIntervention()
            duckingCoordinator.playInterventionWithDucking(
                audioFile = cached.audioFile,
                fallbackText = cached.script
            ) {
                cached.audioFile?.let { try { it.delete() } catch (_: Exception) {} }
            }
            return
        }

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
            val theme = if (!block.themeDescription.isNullOrBlank()) "${block.title}: ${block.themeDescription}" else block.title
            executeIntervention(
                theme = theme,
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
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!coroutineContext.isActive) return@withContext false

            // 1. Generate Script
            val script = scriptGenerator.generateSpeech(
                upcomingSongs = upcomingSongs,
                blockTheme = theme,
                previousSong = previousSong,
                wasPreviousSongListened = wasPreviousSongListened,
                timeoutMs = 8000L
            )
            if (!coroutineContext.isActive) return@withContext false

            _lastSpeechScript.value = script
            Log.i(TAG, "Generated DJ Script: $script")

            // 2. Synthesize Audio via Kokoro TTS (or null if weights missing)
            val audioFile = ttsEngine.synthesizeToWavFile(script)
            if (!coroutineContext.isActive) {
                audioFile?.delete()
                return@withContext false
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
            return@withContext true
        } catch (e: CancellationException) {
            Log.d(TAG, "DJ intervention cancelled.")
            if (songsSinceIntervention < MIN_SONGS_BETWEEN_INTERVENTIONS) {
                songsSinceIntervention = MIN_SONGS_BETWEEN_INTERVENTIONS
            }
            lastIntervenedSongId = null
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error executing DJ intervention", e)
            return@withContext false
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
