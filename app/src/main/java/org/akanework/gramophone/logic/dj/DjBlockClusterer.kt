package org.akanework.gramophone.logic.dj

import androidx.media3.common.MediaItem
import java.util.UUID

class DjBlockClusterer {

    companion object {
        const val DEFAULT_BLOCK_SIZE = 3
    }

    val blockSize: Int = DEFAULT_BLOCK_SIZE

    fun clusterTracks(songs: List<MediaItem>, blockSize: Int = DEFAULT_BLOCK_SIZE): List<DjBlock> {
        if (songs.isEmpty()) return emptyList()

        val blocks = mutableListOf<DjBlock>()
        // Shuffle the pool initially so tracks within blocks are varied and not alphabetical
        val unassigned = songs.shuffled().toMutableList()

        // 1. Cluster by Artist (artists with >= blockSize tracks)
        val songsByArtist = unassigned.groupBy { it.mediaMetadata.artist?.toString()?.trim() }
            .filterKeys { !it.isNullOrBlank() }
            .toList()
            .shuffled()

        for ((artist, artistSongs) in songsByArtist) {
            if (artistSongs.size >= blockSize) {
                val chunk = artistSongs.take(blockSize)
                blocks.add(
                    DjBlock(
                        id = UUID.randomUUID().toString(),
                        title = "Especial $artist",
                        themeDescription = "un recorrido por las mejores canciones de $artist",
                        songs = chunk
                    )
                )
                unassigned.removeAll(chunk)
            }
        }

        // 2. Cluster by Decade
        val songsByDecade = unassigned.groupBy { song ->
            val year = song.mediaMetadata.releaseYear
            if (year != null && year in 1950..2030) {
                (year / 10) * 10
            } else {
                null
            }
        }.filterKeys { it != null }
            .toList()
            .shuffled()

        for ((decade, decadeSongs) in songsByDecade) {
            if (decade != null && decadeSongs.size >= blockSize) {
                val chunk = decadeSongs.take(blockSize)
                blocks.add(
                    DjBlock(
                        id = UUID.randomUUID().toString(),
                        title = "Clásicos de los ${decade}s",
                        themeDescription = "un bloque cargado de recuerdos de los años $decade",
                        songs = chunk
                    )
                )
                unassigned.removeAll(chunk)
            }
        }

        // 3. Cluster by Genre
        val songsByGenre = unassigned.groupBy { it.mediaMetadata.genre?.toString()?.trim() }
            .filterKeys { !it.isNullOrBlank() }
            .toList()
            .shuffled()

        for ((genre, genreSongs) in songsByGenre) {
            if (genreSongs.size >= blockSize) {
                val chunk = genreSongs.take(blockSize)
                blocks.add(
                    DjBlock(
                        id = UUID.randomUUID().toString(),
                        title = "Momento $genre",
                        themeDescription = "un momento especial a puro $genre",
                        songs = chunk
                    )
                )
                unassigned.removeAll(chunk)
            }
        }

        // 4. Group remaining tracks into varied discovery mix blocks
        val remainingChunks = unassigned.shuffled().chunked(blockSize)
        val mixTitles = listOf(
            "Mix Selección de Livi",
            "Variado y con Onda",
            "Joyas de tu Biblioteca",
            "Ritmo Continuo",
            "Mix Sorpresa"
        )

        remainingChunks.forEachIndexed { index, chunk ->
            val title = mixTitles.getOrElse(index % mixTitles.size) { "Mix Local Livi" }
            blocks.add(
                DjBlock(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    themeDescription = "una selección fresca y variada de tu música",
                    songs = chunk
                )
            )
        }

        // Shuffle blocks order so every DJ session is a fresh and unique sequence!
        blocks.shuffle()

        return blocks
    }
}
