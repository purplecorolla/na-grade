package app.nagrade.api.music

// Port of ADR 0003: the rest of the app only knows these types. Provider formats stay inside each adapter
interface MusicProvider {
    // Same id as the OAuth2 registration and connected_accounts.provider
    val id: String

    // Takes the user explicitly (not the session): the radar (Sprint 3) calls it in the background
    fun topArtists(userEmail: String, period: TimePeriod, limit: Int): List<Artist>

    fun artist(userEmail: String, artistId: String): Artist

    // Playlists (#30). Fails fast, before any search, when the connection lacks the playlist scopes
    fun requirePlaylistAccess(userEmail: String)

    // Candidates in the provider's order; picking the right one is a business rule (TrackMatcher)
    fun searchTracks(userEmail: String, title: String, artistName: String): List<Track>

    // Private: the playlist of whoever goes to the show
    fun createPlaylist(userEmail: String, name: String, description: String): Playlist

    fun addPlaylistItems(userEmail: String, playlistId: String, trackUris: List<String>)

    // Replaces every item. PlaylistNotFoundException when the playlist doesn't exist anymore
    fun replacePlaylistItems(userEmail: String, playlistId: String, trackUris: List<String>)

    // Deleting a playlist in the Spotify app only removes it from the user's library
    fun isInLibrary(userEmail: String, playlistId: String): Boolean

    fun playlistUrl(playlistId: String): String
}

data class Artist(val id: String, val name: String, val imageUrl: String?)

// A track in the provider, with all its artists (featured ones too)
data class Track(val uri: String, val name: String, val artists: List<TrackArtist>)

data class TrackArtist(val id: String, val name: String)

data class Playlist(val id: String, val url: String)

enum class TimePeriod { SHORT, MEDIUM, LONG }

class ProviderNotConnectedException(val provider: String) : RuntimeException("$provider is not connected")

// The provider no longer accepts our tokens (refresh denied, access revoked): the connection was removed
class ProviderReconnectRequiredException(val provider: String) : RuntimeException("$provider must be connected again")

class ArtistNotFoundException(val provider: String, val artistId: String) : RuntimeException("$provider has no artist $artistId")

class ProviderUnavailableException(val provider: String, cause: Throwable) : RuntimeException("$provider is unavailable", cause)

// Our own limit for the provider was reached and waiting longer would hold the request too long: try again soon
class ProviderBusyException(val provider: String) : RuntimeException("$provider is busy")

class PlaylistNotFoundException(val provider: String, val playlistId: String) : RuntimeException("$provider has no playlist $playlistId")
