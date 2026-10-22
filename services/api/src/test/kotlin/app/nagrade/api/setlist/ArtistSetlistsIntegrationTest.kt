package app.nagrade.api.setlist

import app.nagrade.api.TestcontainersConfig
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.test.web.servlet.get as mockGet

// WireMock plays both the Spotify Web API (artist name) and the setlist.fm API; Redis and Postgres are real
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
@ExtendWith(OutputCaptureExtension::class)
class ArtistSetlistsIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var authorizedClients: OAuth2AuthorizedClientService

    @Autowired
    lateinit var clientRegistrations: ClientRegistrationRepository

    @Autowired
    lateinit var mappings: ArtistMappingRepository

    @Autowired
    lateinit var redis: StringRedisTemplate

    private val password = "fake-password-123"

    @BeforeEach
    fun reset() {
        wiremock.resetAll()
        redis.keys("setlistfm:*").takeIf { it.isNotEmpty() }?.let { redis.delete(it) }
    }

    private fun json(body: String, status: Int = 200) =
        aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)

    private fun newEmail() = "fan-${UUID.randomUUID()}@nagrade.dev"

    // Spotify ids are 22 base62 characters; a new one per test, since the mapping table is shared
    private fun newSpotifyId() = UUID.randomUUID().toString().replace("-", "").take(22)

    private fun MockHttpServletRequestDsl.withCsrf() {
        val xsrf = checkNotNull(mockMvc.mockGet("/auth/csrf").andReturn().response.getCookie("XSRF-TOKEN"))
        cookie(xsrf)
        header("X-XSRF-TOKEN", xsrf.value)
    }

    private fun sessionFor(email: String): Cookie {
        val body = """{"email":"$email","password":"$password"}"""
        mockMvc.post("/auth/register") {
            withCsrf()
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/auth/login") {
            withCsrf()
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isNoContent() } }
        return checkNotNull(login.andReturn().response.getCookie("SESSION"))
    }

    private fun connectSpotify(email: String) {
        val now = Instant.now()
        val token = OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "fake-access-token", now, now.plus(Duration.ofHours(1)), setOf("user-top-read"))
        authorizedClients.saveAuthorizedClient(
            OAuth2AuthorizedClient(checkNotNull(clientRegistrations.findByRegistrationId("spotify")), email, token, OAuth2RefreshToken("fake-refresh-token", null)),
            UsernamePasswordAuthenticationToken.authenticated(email, null, emptyList()),
        )
    }

    // A logged-in user with Spotify connected
    private fun connectedSession(): Cookie {
        val email = newEmail()
        return sessionFor(email).also { connectSpotify(email) }
    }

    private fun stubSpotifyArtist(spotifyId: String, name: String) {
        wiremock.stubFor(get(urlPathEqualTo("/v1/artists/$spotifyId")).willReturn(json("""{"id":"$spotifyId","name":"$name","type":"artist","images":[]}""")))
    }

    private fun stubSearch(name: String, vararg artists: Pair<String, String>) {
        val items = artists.joinToString(",") { (mbid, artistName) -> """{"mbid":"$mbid","name":"$artistName","sortName":"$artistName"}""" }
        wiremock.stubFor(
            get(urlPathEqualTo("/rest/1.0/search/artists")).withQueryParam("artistName", equalTo(name))
                .willReturn(json("""{"type":"artists","itemsPerPage":30,"page":1,"total":${artists.size},"artist":[$items]}""")),
        )
    }

    private fun stubSetlists(mbid: String, vararg setlists: String) {
        wiremock.stubFor(
            get(urlPathEqualTo("/rest/1.0/artist/$mbid/setlists"))
                .willReturn(json("""{"type":"setlists","itemsPerPage":20,"page":1,"total":${setlists.size},"setlist":[${setlists.joinToString(",")}]}""")),
        )
    }

    private fun setlist(id: String, eventDate: String, sets: String = """[{"song":[{"name":"Fake Song"}]}]""") =
        """
        {"id":"$id","eventDate":"$eventDate","artist":{"mbid":"ignored","name":"ignored"},
         "venue":{"name":"Fake Arena","city":{"name":"São Paulo","country":{"code":"BR","name":"Brazil"}}},
         "tour":{"name":"Fake Tour"},"sets":{"set":$sets},"url":"https://www.setlist.fm/fake"}
        """.trimIndent()

    private fun setlists(session: Cookie, spotifyId: String): ResultActionsDsl =
        mockMvc.mockGet("/artists/$spotifyId/setlists") { cookie(session) }

    @Test
    fun `finds the artist with the identical name, saves the mbid and returns the recent setlists`() {
        val session = connectedSession()
        val spotifyId = newSpotifyId()
        stubSpotifyArtist(spotifyId, "Fake Band")
        stubSearch("Fake Band", "mbid-tribute" to "Fake Band Tribute", "mbid-real" to " fake band ")
        stubSetlists(
            "mbid-real",
            setlist(
                "s1", "23-08-2026",
                """
                [{"name":"","song":[{"name":"Intro","tape":true},{"name":"Opener"},{"name":"Old Hit","cover":{"mbid":"x","name":"Other Band"}}]},
                 {"name":"","encore":1,"song":[{"name":"Closer"}]}]
                """.trimIndent(),
            ),
            setlist("s2", "20-08-2026"),
        )

        setlists(session, spotifyId).andExpect {
            status { isOk() }
            jsonPath("$.artist.spotifyId") { value(spotifyId) }
            jsonPath("$.artist.name") { value("Fake Band") }
            jsonPath("$.artist.mbid") { value("mbid-real") }
            jsonPath("$.setlists", hasSize<Any>(2))
            jsonPath("$.setlists[0].id") { value("s1") }
            jsonPath("$.setlists[0].eventDate") { value("2026-08-23") }
            jsonPath("$.setlists[0].venue") { value("Fake Arena") }
            jsonPath("$.setlists[0].city") { value("São Paulo") }
            jsonPath("$.setlists[0].country") { value("Brazil") }
            jsonPath("$.setlists[0].tour") { value("Fake Tour") }
            jsonPath("$.setlists[0].sets[0].encore") { value(false) }
            jsonPath("$.setlists[0].sets[0].songs[*].name") { value(contains("Intro", "Opener", "Old Hit")) }
            jsonPath("$.setlists[0].sets[0].songs[0].tape") { value(true) }
            jsonPath("$.setlists[0].sets[0].songs[1].tape") { value(false) }
            jsonPath("$.setlists[0].sets[0].songs[1].coverOf") { value(nullValue()) }
            jsonPath("$.setlists[0].sets[0].songs[2].coverOf") { value("Other Band") }
            jsonPath("$.setlists[0].sets[1].encore") { value(true) }
            jsonPath("$.setlists[1].id") { value("s2") }
        }

        wiremock.verify(
            getRequestedFor(urlPathEqualTo("/rest/1.0/search/artists"))
                .withQueryParam("sort", equalTo("relevance"))
                .withHeader("x-api-key", equalTo("fake-setlistfm-api-key"))
                .withHeader("Accept", equalTo("application/json")),
        )
        assertThat(mappings.findById(spotifyId)).hasValueSatisfying {
            assertThat(it.name).isEqualTo("Fake Band")
            assertThat(it.setlistfmMbid).isEqualTo("mbid-real")
        }
    }

    @Test
    fun `the second call skips Spotify and the search, and the setlists come from the cache`() {
        val session = connectedSession()
        val spotifyId = newSpotifyId()
        stubSpotifyArtist(spotifyId, "Fake Band")
        stubSearch("Fake Band", "mbid-cached" to "Fake Band")
        stubSetlists("mbid-cached", setlist("s1", "23-08-2026"))

        repeat(2) {
            setlists(session, spotifyId).andExpect {
                status { isOk() }
                jsonPath("$.setlists[0].id") { value("s1") }
            }
        }

        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/v1/artists/$spotifyId")))
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/rest/1.0/search/artists")))
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/rest/1.0/artist/mbid-cached/setlists")))
        assertThat(redis.getExpire("setlistfm:setlists:mbid-cached")).isBetween(Duration.ofHours(11).seconds, Duration.ofHours(12).seconds)
    }

    @Test
    fun `two artists with the identical name pick the one with the most recent setlist and logs a warning`(output: CapturedOutput) {
        val session = connectedSession()
        val spotifyId = newSpotifyId()
        stubSpotifyArtist(spotifyId, "Twin Band")
        stubSearch("Twin Band", "mbid-old" to "Twin Band", "mbid-new" to "Twin Band")
        stubSetlists("mbid-old", setlist("old", "10-01-2019"))
        stubSetlists("mbid-new", setlist("new", "01-09-2026"))

        setlists(session, spotifyId).andExpect {
            status { isOk() }
            jsonPath("$.artist.mbid") { value("mbid-new") }
            jsonPath("$.setlists[0].id") { value("new") }
        }
        assertThat(output).contains("WARN").contains("Twin Band").contains("mbid-new")
    }

    @Test
    fun `no identical name returns 404 and the empty search is cached`() {
        val session = connectedSession()
        val spotifyId = newSpotifyId()
        stubSpotifyArtist(spotifyId, "Lonely Band")
        stubSearch("Lonely Band", "mbid-tribute" to "Lonely Band Tribute")

        repeat(2) {
            setlists(session, spotifyId).andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("setlist_artist_not_found") }
            }
        }
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/rest/1.0/search/artists")))
        assertThat(mappings.findById(spotifyId)).isEmpty
    }

    @Test
    fun `setlist fm answering 404 means nothing found`() {
        val session = connectedSession()
        val spotifyId = newSpotifyId()
        stubSpotifyArtist(spotifyId, "Unknown Band")
        wiremock.stubFor(get(urlPathEqualTo("/rest/1.0/search/artists")).willReturn(json("""{"code":404,"status":"Not Found","message":"not found"}""", 404)))

        setlists(session, spotifyId).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("setlist_artist_not_found") }
        }
    }

    @Test
    fun `an artist without setlists returns an empty list`() {
        val session = connectedSession()
        val spotifyId = newSpotifyId()
        stubSpotifyArtist(spotifyId, "New Band")
        stubSearch("New Band", "mbid-empty" to "New Band")
        wiremock.stubFor(get(urlPathEqualTo("/rest/1.0/artist/mbid-empty/setlists")).willReturn(json("""{"code":404,"status":"Not Found","message":"not found"}""", 404)))

        setlists(session, spotifyId).andExpect {
            status { isOk() }
            jsonPath("$.artist.mbid") { value("mbid-empty") }
            jsonPath("$.setlists", hasSize<Any>(0))
        }
    }

    @Test
    fun `the daily quota reached returns 503 without calling setlist fm`() {
        val session = connectedSession()
        val spotifyId = newSpotifyId()
        stubSpotifyArtist(spotifyId, "Fake Band")
        redis.opsForValue().set("setlistfm:quota:${LocalDate.now(ZoneOffset.UTC)}", "1400")

        setlists(session, spotifyId).andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value("setlist_quota_exceeded") }
        }
        assertThat(wiremock.findAll(getRequestedFor(urlPathMatching("/rest/1.0/.*")))).isEmpty()
    }

    // The manual test got a 429 from setlist.fm for two calls in the same second: calls are spaced instead
    @Test
    fun `consecutive calls to setlist fm are at least 600 ms apart`() {
        val session = connectedSession()
        val spotifyId = newSpotifyId()
        stubSpotifyArtist(spotifyId, "Fake Band")
        stubSearch("Fake Band", "mbid-spaced" to "Fake Band")
        stubSetlists("mbid-spaced", setlist("s1", "23-08-2026"))

        setlists(session, spotifyId).andExpect { status { isOk() } }

        val times = wiremock.findAll(getRequestedFor(urlPathMatching("/rest/1.0/.*"))).map { it.loggedDate.time }.sorted()
        assertThat(times).hasSize(2)
        // 600 ms between the reserved slots; a little less here for the time WireMock takes to log the first one
        assertThat(times[1] - times[0]).isGreaterThanOrEqualTo(550)
    }

    @Test
    fun `a call waits for its slot when it is less than 2 seconds away`() {
        val session = connectedSession()
        val spotifyId = newSpotifyId()
        stubSpotifyArtist(spotifyId, "Fake Band")
        stubSearch("Fake Band", "mbid-held" to "Fake Band")
        stubSetlists("mbid-held", setlist("s1", "23-08-2026"))
        reserveSlotsFor(Duration.ofSeconds(1))

        setlists(session, spotifyId).andExpect { status { isOk() } }
    }

    @Test
    fun `waiting more than 2 seconds for the rate limit returns 503`() {
        val session = connectedSession()
        val spotifyId = newSpotifyId()
        stubSpotifyArtist(spotifyId, "Fake Band")
        reserveSlotsFor(Duration.ofSeconds(5))

        setlists(session, spotifyId).andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value("provider_unavailable") }
            jsonPath("$.provider") { value("setlistfm") }
        }
        assertThat(wiremock.findAll(getRequestedFor(urlPathMatching("/rest/1.0/.*")))).isEmpty()
    }

    // As if other calls had already taken the slots for this long. The slots use the Redis clock
    private fun reserveSlotsFor(duration: Duration) {
        val redisNow = checkNotNull(redis.execute { it.serverCommands().time() })
        redis.opsForValue().set("setlistfm:next-slot", (redisNow + duration.toMillis()).toString(), Duration.ofSeconds(10))
    }

    @Test
    fun `setlist fm down or rate limiting returns 502`() {
        val session = connectedSession()
        for (status in listOf(500, 429)) {
            val spotifyId = newSpotifyId()
            stubSpotifyArtist(spotifyId, "Fake Band")
            wiremock.stubFor(get(urlPathEqualTo("/rest/1.0/search/artists")).willReturn(json("""{"code":$status}""", status)))

            setlists(session, spotifyId).andExpect {
                status { isBadGateway() }
                jsonPath("$.code") { value("provider_unavailable") }
                jsonPath("$.provider") { value("setlistfm") }
            }
        }
    }

    @Test
    fun `an artist unknown to Spotify returns 404`() {
        val session = connectedSession()
        val spotifyId = newSpotifyId()
        wiremock.stubFor(get(urlPathEqualTo("/v1/artists/$spotifyId")).willReturn(json("""{"error":{"status":404,"message":"Resource not found"}}""", 404)))

        setlists(session, spotifyId).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("artist_not_found") }
        }
    }

    @Test
    fun `an invalid Spotify id returns 400 without calling anyone`() {
        val session = connectedSession()

        for (spotifyId in listOf("short", "has-dashes-and-22-chars", "aaaaaaaaaaaaaaaaaaaaaaa")) {
            setlists(session, spotifyId).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
                jsonPath("$.fields") { value(contains("spotifyId")) }
            }
        }
        assertThat(wiremock.allServeEvents).isEmpty()
    }

    @Test
    fun `without a Spotify connection and no saved mapping returns 409`() {
        val session = sessionFor(newEmail())

        setlists(session, newSpotifyId()).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("provider_not_connected") }
        }
    }

    @Test
    fun `without a login returns 401 without a body code`() {
        mockMvc.mockGet("/artists/${newSpotifyId()}/setlists").andExpect {
            status { isUnauthorized() }
            content { string("") }
        }
    }

    companion object {
        private val wiremock = WireMockServer(options().dynamicPort()).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun uris(registry: DynamicPropertyRegistry) {
            registry.add("app.spotify.api-base-url") { "${wiremock.baseUrl()}/v1" }
            registry.add("app.setlistfm.api-base-url") { "${wiremock.baseUrl()}/rest/1.0" }
        }

        @JvmStatic
        @AfterAll
        fun stopWiremock() = wiremock.stop()
    }
}
