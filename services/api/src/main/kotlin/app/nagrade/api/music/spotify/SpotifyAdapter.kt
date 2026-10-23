package app.nagrade.api.music.spotify

import app.nagrade.api.music.Artist
import app.nagrade.api.music.ArtistNotFoundException
import app.nagrade.api.music.MusicProvider
import app.nagrade.api.music.Playlist
import app.nagrade.api.music.PlaylistNotFoundException
import app.nagrade.api.music.ProviderNotConnectedException
import app.nagrade.api.music.ProviderReconnectRequiredException
import app.nagrade.api.music.ProviderUnavailableException
import app.nagrade.api.music.TimePeriod
import app.nagrade.api.music.Track
import app.nagrade.api.music.TrackArtist
import app.nagrade.api.music.TrackSearchFailedException
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import java.time.Duration

// Spotify Web API after the February 2026 changes: artist `popularity` and `followers` are gone and `genres`
// is deprecated, so only id, name and images are read
@Component
class SpotifyAdapter(
    restClientBuilder: RestClient.Builder,
    @Value("\${app.spotify.api-base-url}") apiBaseUrl: String,
    private val authorizedClientManager: OAuth2AuthorizedClientManager,
    private val authorizedClients: OAuth2AuthorizedClientService,
) : MusicProvider {

    override val id = "spotify"

    private val restClient = restClientBuilder.baseUrl(apiBaseUrl).build()

    override fun topArtists(userEmail: String, period: TimePeriod, limit: Int): List<Artist> {
        val accessToken = accessToken(userEmail)
        val page = call(userEmail) {
            restClient.get()
                .uri { it.path("/me/top/artists").queryParam("time_range", period.toTimeRange()).queryParam("limit", limit).build() }
                .headers { it.setBearerAuth(accessToken) }
                .retrieve()
                .body<TopArtistsPage>()
        }
        // images come widest first
        return page?.items.orEmpty().map { Artist(it.id, it.name, it.images.firstOrNull()?.url) }
    }

    // GET /artists/{id} is still there for development mode apps; only the batch GET /artists was removed (fev/2026)
    override fun artist(userEmail: String, artistId: String): Artist {
        val accessToken = accessToken(userEmail)
        val artist = call(userEmail) {
            try {
                restClient.get()
                    .uri("/artists/{id}", artistId)
                    .headers { it.setBearerAuth(accessToken) }
                    .retrieve()
                    .body<SpotifyArtist>()
            } catch (e: HttpClientErrorException.NotFound) {
                null
            }
        } ?: throw ArtistNotFoundException(id, artistId)
        return Artist(artist.id, artist.name, artist.images.firstOrNull()?.url)
    }

    // Connections made before #30 lack playlist-read-private (needed to see if a playlist is still in the library):
    // the user connects again, like after a revoked access
    override fun requirePlaylistAccess(userEmail: String) {
        val client = authorizedClients.loadAuthorizedClient<OAuth2AuthorizedClient>(id, userEmail) ?: throw ProviderNotConnectedException(id)
        if (!client.accessToken.scopes.containsAll(PLAYLIST_SCOPES)) {
            authorizedClients.removeAuthorizedClient(id, userEmail)
            throw ProviderReconnectRequiredException(id)
        }
    }

    // limit is at most 10 since fev/2026. Only letters, digits and apostrophes go into the field filters: Spotify
    // answered 503 to titles with "/", "!", "$" or "." (manual test of #30). TrackMatcher compares the real titles
    override fun searchTracks(userEmail: String, title: String, artistName: String): List<Track> {
        val accessToken = accessToken(userEmail)
        val query = "track:\"${searchTerm(title)}\" artist:\"${searchTerm(artistName)}\""
        val page = call(userEmail) {
            try {
                restClient.get()
                    .uri { it.path("/search").queryParam("q", "{q}").queryParam("type", "track").queryParam("limit", SEARCH_LIMIT).build(query) }
                    .headers { it.setBearerAuth(accessToken) }
                    .retrieve()
                    .body<SearchPage>()
            } catch (e: HttpServerErrorException) {
                throw TrackSearchFailedException(id, title, e)
            }
        }
        return page?.tracks?.items.orEmpty().filterNotNull().map { track ->
            Track(track.uri, track.name, track.artists.map { TrackArtist(it.id, it.name) })
        }
    }

    // POST /me/playlists replaced POST /users/{id}/playlists in fev/2026
    override fun createPlaylist(userEmail: String, name: String, description: String): Playlist {
        val accessToken = accessToken(userEmail)
        val created = call(userEmail) {
            restClient.post()
                .uri("/me/playlists")
                .headers { it.setBearerAuth(accessToken) }
                .body(mapOf("name" to name, "public" to false, "description" to description))
                .retrieve()
                .body<CreatedPlaylist>()
        } ?: throw ProviderUnavailableException(id, IllegalStateException("empty playlist response"))
        return Playlist(created.id, created.externalUrls?.spotify ?: playlistUrl(created.id))
    }

    override fun addPlaylistItems(userEmail: String, playlistId: String, trackUris: List<String>) {
        val accessToken = accessToken(userEmail)
        trackUris.chunked(MAX_ITEMS_PER_REQUEST).forEach { sendItems(userEmail, accessToken, playlistId, it, replace = false) }
    }

    // PUT sets the first 100 items; any others are added after them
    override fun replacePlaylistItems(userEmail: String, playlistId: String, trackUris: List<String>) {
        val accessToken = accessToken(userEmail)
        trackUris.chunked(MAX_ITEMS_PER_REQUEST).ifEmpty { listOf(emptyList()) }.forEachIndexed { index, chunk ->
            sendItems(userEmail, accessToken, playlistId, chunk, replace = index == 0)
        }
    }

    private fun sendItems(userEmail: String, accessToken: String, playlistId: String, uris: List<String>, replace: Boolean) {
        call(userEmail) {
            try {
                (if (replace) restClient.put() else restClient.post())
                    .uri("/playlists/{id}/items", playlistId)
                    .headers { it.setBearerAuth(accessToken) }
                    .body(mapOf("uris" to uris))
                    .retrieve()
                    .toBodilessEntity()
            } catch (e: HttpClientErrorException.NotFound) {
                throw PlaylistNotFoundException(id, playlistId)
            }
        }
    }

    override fun isInLibrary(userEmail: String, playlistId: String): Boolean {
        val accessToken = accessToken(userEmail)
        val contains = call(userEmail) {
            try {
                restClient.get()
                    .uri { it.path("/me/library/contains").queryParam("uris", "{uri}").build("spotify:playlist:$playlistId") }
                    .headers { it.setBearerAuth(accessToken) }
                    .retrieve()
                    .body<List<Boolean>>()
            } catch (e: HttpClientErrorException.Forbidden) {
                // Missing scope despite requirePlaylistAccess (the grant changed on Spotify's side)
                authorizedClients.removeAuthorizedClient(id, userEmail)
                throw ProviderReconnectRequiredException(id)
            }
        }
        return contains?.firstOrNull() == true
    }

    private fun searchTerm(text: String) = text.replace(SEARCH_SYMBOLS, " ").replace(SPACES, " ").trim()

    override fun playlistUrl(playlistId: String) = "https://open.spotify.com/playlist/$playlistId"

    // Refreshes the access token when it is about to expire (OAuth2ClientConfig) and saves the new one
    private fun accessToken(userEmail: String): String {
        val request = OAuth2AuthorizeRequest.withClientRegistrationId(id).principal(userEmail).build()
        val client = try {
            authorizedClientManager.authorize(request)
        } catch (e: OAuth2AuthorizationException) {
            // invalid_grant: the manager already removed the connection
            throw ProviderReconnectRequiredException(id)
        }
        return client?.accessToken?.tokenValue ?: throw ProviderNotConnectedException(id)
    }

    private fun <T> call(userEmail: String, request: () -> T): T = try {
        retryingOnceOn429(request)
    } catch (e: HttpClientErrorException.Unauthorized) {
        // Token still valid for us but refused by Spotify: the user revoked the app at spotify.com/account/apps
        authorizedClients.removeAuthorizedClient(id, userEmail)
        throw ProviderReconnectRequiredException(id)
    } catch (e: RestClientException) {
        throw ProviderUnavailableException(id, e)
    }

    // A playlist takes about 20 searches. On a 429 Spotify says when to try again (Retry-After, in seconds): once,
    // and only for a short wait; a second 429 becomes provider_unavailable
    private fun <T> retryingOnceOn429(request: () -> T): T = try {
        request()
    } catch (e: HttpClientErrorException.TooManyRequests) {
        val wait = e.responseHeaders?.getFirst(HttpHeaders.RETRY_AFTER)?.toLongOrNull() ?: 1
        if (wait > MAX_RETRY_AFTER_SECONDS) throw e
        Thread.sleep(Duration.ofSeconds(wait))
        request()
    }

    private fun TimePeriod.toTimeRange() = when (this) {
        TimePeriod.SHORT -> "short_term"
        TimePeriod.MEDIUM -> "medium_term"
        TimePeriod.LONG -> "long_term"
    }

    private companion object {
        val PLAYLIST_SCOPES = setOf("playlist-modify-private", "playlist-read-private")
        const val SEARCH_LIMIT = 10
        val SEARCH_SYMBOLS = Regex("""[^\p{L}\p{N}\s'’]""")
        val SPACES = Regex("""\s+""")
        const val MAX_ITEMS_PER_REQUEST = 100
        const val MAX_RETRY_AFTER_SECONDS = 5L
    }

    private data class SearchPage(val tracks: TracksPage? = null)

    private data class TracksPage(val items: List<SpotifyTrack?> = emptyList())

    private data class SpotifyTrack(val name: String, val uri: String, val artists: List<SpotifyTrackArtist> = emptyList())

    private data class SpotifyTrackArtist(val id: String, val name: String)

    private data class CreatedPlaylist(val id: String, @param:JsonProperty("external_urls") val externalUrls: ExternalUrls? = null)

    private data class ExternalUrls(val spotify: String? = null)

    private data class TopArtistsPage(val items: List<SpotifyArtist> = emptyList())

    private data class SpotifyArtist(val id: String, val name: String, val images: List<SpotifyImage> = emptyList())

    private data class SpotifyImage(val url: String)
}
