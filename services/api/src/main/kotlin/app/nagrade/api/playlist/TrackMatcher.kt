package app.nagrade.api.playlist

import app.nagrade.api.music.SongTitle
import app.nagrade.api.music.Track

// Picks the track of a predicted song among the search results, in the provider's order
object TrackMatcher {

    // By the artist id we already have: the exact title first (the studio version), then a version of the same
    // song (live, remaster...), with the same normalizer as the prediction (SongTitle)
    fun byArtist(results: List<Track>, title: String, artistId: String): Track? {
        val byTheArtist = results.filter { track -> track.artists.any { it.id == artistId } }
        return byTheArtist.firstOrNull { SongTitle.strict(it.name) == SongTitle.strict(title) }
            ?: byTheArtist.firstOrNull { SongTitle.normalize(it.name) == SongTitle.normalize(title) }
    }

    // Original version of a cover: only the artist name is known, so the title is strict
    fun byArtistName(results: List<Track>, title: String, artistName: String): Track? =
        results.firstOrNull { track ->
            SongTitle.strict(track.name) == SongTitle.strict(title) && track.artists.any { SongTitle.strict(it.name) == SongTitle.strict(artistName) }
        }
}
