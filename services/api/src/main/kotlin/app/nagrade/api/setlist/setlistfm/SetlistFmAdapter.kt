package app.nagrade.api.setlist.setlistfm

import app.nagrade.api.music.ProviderUnavailableException
import app.nagrade.api.setlist.Setlist
import app.nagrade.api.setlist.SetlistSet
import app.nagrade.api.setlist.SetlistSource
import app.nagrade.api.setlist.Song
import app.nagrade.api.setlist.SourceArtist
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import org.springframework.web.util.UriBuilder
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// setlist.fm API 1.0. The responses are cached raw in Redis (the free key allows 1440 requests a day) and parsed
// on every read: no serializer config for the domain types. A 404 means "nothing found" and is cached too
@Component
class SetlistFmAdapter(
    restClientBuilder: RestClient.Builder,
    @Value("\${app.setlistfm.api-base-url}") apiBaseUrl: String,
    @Value("\${app.setlistfm.api-key}") apiKey: String,
    private val rateLimiter: SetlistFmRateLimiter,
    private val redis: StringRedisTemplate,
    private val jsonMapper: JsonMapper,
) : SetlistSource {

    override val id = "setlistfm"

    // Without Accept: application/json the API answers XML
    private val restClient = restClientBuilder.baseUrl(apiBaseUrl)
        .defaultHeader("x-api-key", apiKey)
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .build()

    override fun searchArtists(name: String): List<SourceArtist> {
        val body = cached("setlistfm:search:${name.trim().lowercase()}", SEARCH_TTL) {
            fetch { it.path("/search/artists").queryParam("artistName", "{name}").queryParam("sort", "relevance").build(name) }
        }
        return parse<ArtistsPage>(body)?.artist.orEmpty().map { SourceArtist(it.mbid, it.name) }
    }

    // First page: the 20 most recent shows
    override fun recentSetlists(artistId: String): List<Setlist> {
        val body = cached("setlistfm:setlists:$artistId", SETLISTS_TTL) {
            fetch { it.path("/artist/{mbid}/setlists").queryParam("p", 1).build(artistId) }
        }
        return parse<SetlistsPage>(body)?.setlist.orEmpty().map { it.toSetlist() }
    }

    private fun cached(key: String, ttl: Duration, fetch: () -> String?): String {
        redis.opsForValue().get(key)?.let { return it }
        return (fetch() ?: NOT_FOUND).also { redis.opsForValue().set(key, it, ttl) }
    }

    private fun fetch(uri: (UriBuilder) -> URI): String? {
        rateLimiter.acquire()
        return try {
            restClient.get().uri(uri).retrieve().body<String>()
        } catch (e: HttpClientErrorException.NotFound) {
            null
        } catch (e: RestClientException) {
            throw ProviderUnavailableException(id, e)
        }
    }

    private inline fun <reified T> parse(body: String): T? = body.takeIf { it != NOT_FOUND }?.let { jsonMapper.readValue<T>(it) }

    private fun FmSetlist.toSetlist() = Setlist(
        id = id,
        eventDate = LocalDate.parse(eventDate, EVENT_DATE),
        venue = venue?.name,
        city = venue?.city?.name,
        country = venue?.city?.country?.name,
        tour = tour?.name,
        sets = sets?.set.orEmpty().map { set ->
            SetlistSet(
                name = set.name?.takeIf { it.isNotBlank() },
                encore = (set.encore ?: 0) > 0,
                songs = set.song.map { Song(it.name, it.tape, it.cover?.name) },
            )
        },
    )

    private companion object {
        const val NOT_FOUND = ""
        val SEARCH_TTL: Duration = Duration.ofHours(24)

        // A new show shows up the same day
        val SETLISTS_TTL: Duration = Duration.ofHours(12)
        val EVENT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    }

    private data class ArtistsPage(val artist: List<FmArtist> = emptyList())

    private data class FmArtist(val mbid: String, val name: String)

    private data class SetlistsPage(val setlist: List<FmSetlist> = emptyList())

    private data class FmSetlist(val id: String, val eventDate: String, val venue: FmVenue? = null, val tour: FmName? = null, val sets: FmSets? = null)

    private data class FmVenue(val name: String? = null, val city: FmCity? = null)

    private data class FmCity(val name: String? = null, val country: FmName? = null)

    private data class FmName(val name: String? = null)

    private data class FmSets(val set: List<FmSet> = emptyList())

    // encore: 1 for the first encore, 2 for the second...
    private data class FmSet(val name: String? = null, val encore: Int? = null, val song: List<FmSong> = emptyList())

    private data class FmSong(val name: String = "", val tape: Boolean = false, val cover: FmName? = null)
}
