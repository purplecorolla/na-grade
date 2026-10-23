package app.nagrade.api.playlist

import app.nagrade.api.music.MusicProvider
import app.nagrade.api.music.PlaylistNotFoundException
import app.nagrade.api.music.ProviderUnavailableException
import app.nagrade.api.music.TrackSearchFailedException
import app.nagrade.api.prediction.PredictedSong
import app.nagrade.api.prediction.SetlistPredictor
import app.nagrade.api.setlist.ArtistSetlistsService
import app.nagrade.api.setlist.MappedArtist
import app.nagrade.api.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// The predicted setlist (#29) as a playlist in the user's music provider (#30). One playlist per user, artist and
// show: generating again updates it, and one the user deleted is created again
@Service
class PlaylistService(
    private val artistSetlists: ArtistSetlistsService,
    private val musicProvider: MusicProvider,
    private val users: UserRepository,
    private val playlists: GeneratedPlaylistRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun generate(userEmail: String, spotifyId: String, showDate: LocalDate): GeneratedPlaylistResult {
        musicProvider.requirePlaylistAccess(userEmail)
        val recent = artistSetlists.recentSetlists(userEmail, spotifyId)
        val prediction = SetlistPredictor.predict(recent.setlists, LocalDate.now(ZoneOffset.UTC))
        val basedOn = prediction.basedOn
        if (prediction.songs.isEmpty() || basedOn == null) throw NoRecentSetlistsException()

        // The searches run before the transaction, so the row stays locked only while the playlist is written.
        // A search that fails on the provider's side leaves that song out; all of them failing means it is down
        val failures = mutableListOf<TrackSearchFailedException>()
        val matches = prediction.songs.associateWith { song ->
            try {
                findTrack(userEmail, song, recent.artist)
            } catch (e: TrackSearchFailedException) {
                log.warn("{} search failed for \"{}\": {}", e.provider, e.title, e.cause?.message)
                failures += e
                null
            }
        }
        if (failures.size == prediction.songs.size) throw ProviderUnavailableException(musicProvider.id, failures.last())
        val tracks = matches.mapNotNull { (_, track) -> track }
        val notFound = matches.filterValues { it == null }.keys.map { it.title }
        if (tracks.isEmpty()) throw NoTracksFoundException(notFound)

        val name = "Na Grade · ${recent.artist.name} · ${showDate.format(NAME_DATE)}"
        // setlist.fm asks for attribution in the API terms
        val description = "Setlist prevista com base em ${basedOn.shows} shows · dados: setlist.fm · Na Grade"
        val userId = checkNotNull(users.findByEmail(userEmail)?.id)
        val uris = tracks.map { it.uri }

        return checkNotNull(
            transaction.execute {
                // A double click waits on reserve until the first request commits, then finds the playlist and updates it
                playlists.reserve(userId, musicProvider.id, spotifyId, showDate)
                val record = checkNotNull(playlists.lock(userId, spotifyId, showDate))
                val existing = record.playlistId?.takeIf { updated(userEmail, it, uris) }
                val playlistId = existing ?: musicProvider.createPlaylist(userEmail, name, description).id
                    .also { musicProvider.addPlaylistItems(userEmail, it, uris) }
                record.playlistId = playlistId
                record.updatedAt = Instant.now()
                GeneratedPlaylistResult(playlistId, musicProvider.playlistUrl(playlistId), name, created = existing == null, tracks, notFound)
            },
        )
    }

    // False when the playlist is gone: removed from the library in the Spotify app, or not found at all
    private fun updated(userEmail: String, playlistId: String, uris: List<String>): Boolean {
        if (!musicProvider.isInLibrary(userEmail, playlistId)) return false
        return try {
            musicProvider.replacePlaylistItems(userEmail, playlistId, uris)
            true
        } catch (e: PlaylistNotFoundException) {
            false
        }
    }

    // The artist's own track first; a cover the band never recorded falls back to the original artist
    private fun findTrack(userEmail: String, song: PredictedSong, artist: MappedArtist): PlaylistTrack? {
        TrackMatcher.byArtist(musicProvider.searchTracks(userEmail, song.title, artist.name), song.title, artist.spotifyId)
            ?.let { return PlaylistTrack(song.title, it.uri, originalVersion = false) }
        val originalArtist = song.coverOf ?: return null
        return TrackMatcher.byArtistName(musicProvider.searchTracks(userEmail, song.title, originalArtist), song.title, originalArtist)
            ?.let { PlaylistTrack(song.title, it.uri, originalVersion = true) }
    }

    private companion object {
        val log = LoggerFactory.getLogger(PlaylistService::class.java)
        val NAME_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}

// originalVersion: a cover found only by the original artist, not as played by the band
data class PlaylistTrack(val title: String, val uri: String, val originalVersion: Boolean)

data class GeneratedPlaylistResult(
    val playlistId: String,
    val url: String,
    val name: String,
    val created: Boolean,
    val tracks: List<PlaylistTrack>,
    val notFound: List<String>,
)

class NoRecentSetlistsException : RuntimeException("No recent setlist to predict from")

class NoTracksFoundException(val notFound: List<String>) : RuntimeException("No predicted song was found")
