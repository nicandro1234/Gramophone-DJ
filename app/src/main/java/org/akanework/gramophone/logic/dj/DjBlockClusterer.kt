package org.akanework.gramophone.logic.dj

import androidx.media3.common.MediaItem
import java.util.UUID

class DjBlockClusterer {

    fun clusterTracks(songs: List<MediaItem>, blockSize: Int = 3): List<DjBlock> {
        if (songs.isEmpty()) return emptyList()

        val blocks = mutableListOf<DjBlock>()
        val unassigned = songs.toMutableList()

        // 1. Cluster by Artist (if artist has >= blockSize songs)
        val songsByArtist = unassigned.groupBy { it.mediaMetadata.artist?.toString()?.trim() }
            .filterKeys { !it.isNullOrBlank() }

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

        for ((decade, decadeSongs) in songsByDecade) {
            if (decadeSongs.size >= blockSize) {
                val chunk = decadeSongs.take(blockSize)
                blocks.add(
                    DjBlock(
                        id = UUID.randomUUID().toString(),
                        title = "Viaje a los ${decade}s",
                        themeDescription = "un bloque cargado de nostalgia de los años $decade",
                        songs = chunk
                    )
                )
                unassigned.removeAll(chunk)
            }
        }

        // 3. Cluster by Genre
        val songsByGenre = unassigned.groupBy { it.mediaMetadata.genre?.toString()?.trim() }
            .filterKeys { !it.isNullOrBlank() }

        for ((genre, genreSongs) in songsByGenre) {
            if (genreSongs.size >= blockSize) {
                val chunk = genreSongs.take(blockSize)
                blocks.add(
                    DjBlock(
                        id = UUID.randomUUID().toString(),
                        title = "Momento $genre",
                        themeDescription = "las mejores pistas seleccionadas del género $genre",
                        songs = chunk
                    )
                )
                unassigned.removeAll(chunk)
            }
        }

        // 4. Group remaining into "Mix Descubrimiento" / "Selección Especial"
        unassigned.chunked(blockSize).forEachIndexed { index, chunk ->
            blocks.add(
                DjBlock(
                    id = UUID.randomUUID().toString(),
                    title = if (index == 0) "Mix Local Livi" else "Selección Variada",
                    themeDescription = "una mezcla variada y fresca de tu biblioteca local",
                    songs = chunk
                )
            )
        }

        return blocks
    }
}
