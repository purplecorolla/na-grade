package app.nagrade.api.music.spotify

import app.nagrade.api.music.Artist
import app.nagrade.api.music.MusicProvider
import app.nagrade.api.music.ProviderNotConnectedException
import app.nagrade.api.music.ProviderReconnectRequiredException
import app.nagrade.api.music.ProviderUnavailableException
import app.nagrade.api.music.TimePeriod
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body

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
        request()
    } catch (e: HttpClientErrorException.Unauthorized) {
        // Token still valid for us but refused by Spotify: the user revoked the app at spotify.com/account/apps
        authorizedClients.removeAuthorizedClient(id, userEmail)
        throw ProviderReconnectRequiredException(id)
    } catch (e: RestClientException) {
        throw ProviderUnavailableException(id, e)
    }

    private fun TimePeriod.toTimeRange() = when (this) {
        TimePeriod.SHORT -> "short_term"
        TimePeriod.MEDIUM -> "medium_term"
        TimePeriod.LONG -> "long_term"
    }

    private data class TopArtistsPage(val items: List<SpotifyArtist> = emptyList())

    private data class SpotifyArtist(val id: String, val name: String, val images: List<SpotifyImage> = emptyList())

    private data class SpotifyImage(val url: String)
}
