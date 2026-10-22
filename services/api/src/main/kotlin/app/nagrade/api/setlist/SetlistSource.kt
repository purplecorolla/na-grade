package app.nagrade.api.setlist

import java.time.LocalDate

// Port for the setlist sources, same pattern as MusicProvider (ADR 0003). setlist.fm is the first adapter
interface SetlistSource {
    val id: String

    // Candidates in the source's relevance order; picking the right one is a business rule (ArtistSetlistsService)
    fun searchArtists(name: String): List<SourceArtist>

    // Most recent first
    fun recentSetlists(artistId: String): List<Setlist>
}

data class SourceArtist(val id: String, val name: String)

data class Setlist(
    val id: String,
    val eventDate: LocalDate,
    val venue: String?,
    val city: String?,
    val country: String?,
    val tour: String?,
    val sets: List<SetlistSet>,
)

data class SetlistSet(val name: String?, val encore: Boolean, val songs: List<Song>)

// tape: recorded intro or outro played on stage. coverOf: original artist when the song is a cover
data class Song(val name: String, val tape: Boolean, val coverOf: String?)

class SetlistQuotaExceededException(val source: String) : RuntimeException("$source daily quota exceeded")

class SetlistArtistNotFoundException(val name: String) : RuntimeException("No setlist artist named $name")
