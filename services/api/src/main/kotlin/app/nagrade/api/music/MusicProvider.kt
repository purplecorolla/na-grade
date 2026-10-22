package app.nagrade.api.music

// Port of ADR 0003: the rest of the app only knows these types. Provider formats stay inside each adapter
interface MusicProvider {
    // Same id as the OAuth2 registration and connected_accounts.provider
    val id: String

    // Takes the user explicitly (not the session): the radar (Sprint 3) calls it in the background
    fun topArtists(userEmail: String, period: TimePeriod, limit: Int): List<Artist>

    fun artist(userEmail: String, artistId: String): Artist
}

data class Artist(val id: String, val name: String, val imageUrl: String?)

enum class TimePeriod { SHORT, MEDIUM, LONG }

class ProviderNotConnectedException(val provider: String) : RuntimeException("$provider is not connected")

// The provider no longer accepts our tokens (refresh denied, access revoked): the connection was removed
class ProviderReconnectRequiredException(val provider: String) : RuntimeException("$provider must be connected again")

class ArtistNotFoundException(val provider: String, val artistId: String) : RuntimeException("$provider has no artist $artistId")

class ProviderUnavailableException(val provider: String, cause: Throwable) : RuntimeException("$provider is unavailable", cause)

// Our own limit for the provider was reached and waiting longer would hold the request too long: try again soon
class ProviderBusyException(val provider: String) : RuntimeException("$provider is busy")
