package org.akanework.gramophone.logic.dj

import androidx.media3.common.MediaItem

data class DjSongInfo(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val year: Int?,
    val genre: String?
)

fun MediaItem.toDjSongInfo(): DjSongInfo {
    return DjSongInfo(
        mediaId = mediaId,
        title = mediaMetadata.title?.toString() ?: "Desconocido",
        artist = mediaMetadata.artist?.toString() ?: "Artista Desconocido",
        album = mediaMetadata.albumTitle?.toString(),
        year = mediaMetadata.releaseYear,
        genre = mediaMetadata.genre?.toString()
    )
}

data class DjBlock(
    val id: String,
    val title: String,
    val themeDescription: String,
    val songs: List<MediaItem>
)

data class DjSpeechIntervention(
    val id: String,
    val script: String,
    val audioFilePath: String?,
    val blockTitle: String
)

enum class DjDuckingState {
    IDLE,
    DUCKING_ACTIVE,
    RESTORING
}
