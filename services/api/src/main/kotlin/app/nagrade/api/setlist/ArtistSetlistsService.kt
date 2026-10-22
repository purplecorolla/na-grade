package app.nagrade.api.setlist

import app.nagrade.api.music.MusicProvider
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class ArtistSetlistsService(
    private val mappings: ArtistMappingRepository,
    private val musicProvider: MusicProvider,
    private val setlistSource: SetlistSource,
) {

    fun recentSetlists(userEmail: String, spotifyId: String): ArtistSetlists {
        val mapping = mappings.findByIdOrNull(spotifyId) ?: map(userEmail, spotifyId)
        return ArtistSetlists(
            MappedArtist(spotifyId, mapping.name, mapping.setlistfmMbid),
            setlistSource.recentSetlists(mapping.setlistfmMbid),
        )
    }

    // Searching by name also brings cover and tribute bands: only an identical name is accepted (spike, docs/spikes/apis.md)
    private fun map(userEmail: String, spotifyId: String): ArtistMapping {
        val name = musicProvider.artist(userEmail, spotifyId).name
        val identical = setlistSource.searchArtists(name).filter { it.name.trim().equals(name.trim(), ignoreCase = true) }
        val chosen = when (identical.size) {
            0 -> throw SetlistArtistNotFoundException(name)
            1 -> identical.single()
            else -> mostRecentlyActive(identical).also {
                log.warn("{} artists named '{}' on {}: picked {}, the one with the most recent setlist", identical.size, name, setlistSource.id, it.id)
            }
        }
        return mappings.save(ArtistMapping(spotifyId, name, chosen.id))
    }

    // Two bands with the same name (e.g. Nirvana): the one still playing is the likely match. An exact match
    // through MusicBrainz is in the Backlog (#48). On a tie, the first by relevance wins
    private fun mostRecentlyActive(candidates: List<SourceArtist>): SourceArtist =
        candidates.maxBy { setlistSource.recentSetlists(it.id).firstOrNull()?.eventDate ?: LocalDate.MIN }

    private companion object {
        val log = LoggerFactory.getLogger(ArtistSetlistsService::class.java)
    }
}

data class ArtistSetlists(val artist: MappedArtist, val setlists: List<Setlist>)

data class MappedArtist(val spotifyId: String, val name: String, val mbid: String)
