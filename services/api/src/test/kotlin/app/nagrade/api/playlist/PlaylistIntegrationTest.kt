package app.nagrade.api.playlist

import app.nagrade.api.TestcontainersConfig
import app.nagrade.api.connection.ConnectedAccountRepository
import app.nagrade.api.user.UserRepository
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.stubbing.Scenario
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.springframework.test.web.servlet.get as mockGet

// WireMock plays the Spotify Web API and setlist.fm. The prediction rules are covered by SetlistPredictorTest and
// the track choice by TrackMatcherTest; here, the playlist flow end to end
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class PlaylistIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var authorizedClients: OAuth2AuthorizedClientService

    @Autowired
    lateinit var clientRegistrations: ClientRegistrationRepository

    @Autowired
    lateinit var playlists: GeneratedPlaylistRepository

    @Autowired
    lateinit var users: UserRepository

    @Autowired
    lateinit var accounts: ConnectedAccountRepository

    @Autowired
    lateinit var redis: StringRedisTemplate

    private val password = "fake-password-123"
    private val showDate = LocalDate.of(2026, 11, 10)

    @BeforeEach
    fun reset() {
        wiremock.resetAll()
        redis.keys("setlistfm:*").takeIf { it.isNotEmpty() }?.let { redis.delete(it) }
    }

    private fun json(body: String, status: Int = 200) =
        aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)

    private fun newEmail() = "fan-${UUID.randomUUID()}@nagrade.dev"

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

    private val allScopes = setOf("user-top-read", "playlist-modify-public", "playlist-modify-private", "playlist-read-private")

    private fun connectSpotify(email: String, scopes: Set<String> = allScopes) {
        val now = Instant.now()
        val token = OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "fake-access-token", now, now.plus(Duration.ofHours(1)), scopes)
        authorizedClients.saveAuthorizedClient(
            OAuth2AuthorizedClient(checkNotNull(clientRegistrations.findByRegistrationId("spotify")), email, token, OAuth2RefreshToken("fake-refresh-token", null)),
            UsernamePasswordAuthenticationToken.authenticated(email, null, emptyList()),
        )
    }

    private data class Fan(val email: String, val session: Cookie)

    private fun connectedFan(): Fan {
        val email = newEmail()
        return Fan(email, sessionFor(email)).also { connectSpotify(email) }
    }

    // A band with 3 recent shows: Opener, Hit, Old Hit (a cover of Other Band), a medley and Closer in the encore
    private fun stubBand(spotifyId: String, mbid: String = "mbid-$spotifyId", sets: String = DEFAULT_SETS) {
        wiremock.stubFor(get(urlPathEqualTo("/v1/artists/$spotifyId")).willReturn(json("""{"id":"$spotifyId","name":"Fake Band","images":[]}""")))
        wiremock.stubFor(
            get(urlPathEqualTo("/rest/1.0/search/artists"))
                .willReturn(json("""{"artist":[{"mbid":"$mbid","name":"Fake Band"}]}""")),
        )
        val shows = (1..3).joinToString(",") {
            val date = LocalDate.now().minusDays(it * 7L).format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
            """{"id":"s$it","eventDate":"$date","venue":{"name":"Fake Arena"},"sets":{"set":$sets}}"""
        }
        wiremock.stubFor(get(urlPathEqualTo("/rest/1.0/artist/$mbid/setlists")).willReturn(json("""{"setlist":[$shows]}""")))
    }

    private fun stubSearch(title: String, artistName: String, vararg tracks: String) {
        wiremock.stubFor(
            get(urlPathEqualTo("/v1/search"))
                .withQueryParam("q", equalTo("""track:"$title" artist:"$artistName""""))
                .withQueryParam("type", equalTo("track"))
                .willReturn(json("""{"tracks":{"items":[${tracks.joinToString(",")}]}}""")),
        )
    }

    private fun track(id: String, name: String, artistId: String, artistName: String = "Fake Band") =
        """{"id":"$id","name":"$name","uri":"spotify:track:$id","artists":[{"id":"$artistId","name":"$artistName"}]}"""

    // Opener and Closer by the band, Hit only as a remaster, Old Hit only by the original artist, the medley nowhere
    private fun stubSpotifyTracks(spotifyId: String) {
        stubSearch("Opener", "Fake Band", track("t-opener", "Opener", spotifyId))
        stubSearch("Hit", "Fake Band", track("t-hit", "Hit - Remastered 2011", spotifyId))
        stubSearch("Old Hit", "Fake Band")
        stubSearch("Old Hit", "Other Band", track("t-old-hit", "Old Hit", "other-id", "Other Band"))
        stubSearch("Medley A Medley B", "Fake Band")
        stubSearch("Closer", "Fake Band", track("t-closer", "Closer", spotifyId))
    }

    private fun stubCreatePlaylist(id: String) {
        wiremock.stubFor(post(urlPathEqualTo("/v1/me/playlists")).willReturn(json("""{"id":"$id","external_urls":{"spotify":"https://open.spotify.com/playlist/$id"}}""", 201)))
        wiremock.stubFor(post(urlPathEqualTo("/v1/playlists/$id/items")).willReturn(json("""{"snapshot_id":"snap"}""", 201)))
    }

    private fun stubInLibrary(playlistId: String, inLibrary: Boolean) {
        wiremock.stubFor(
            get(urlPathEqualTo("/v1/me/library/contains")).withQueryParam("uris", equalTo("spotify:playlist:$playlistId"))
                .willReturn(json("[$inLibrary]")),
        )
    }

    private fun generate(session: Cookie, spotifyId: String, date: String = showDate.toString()): ResultActionsDsl =
        mockMvc.post("/artists/$spotifyId/playlist") {
            cookie(session)
            withCsrf()
            contentType = MediaType.APPLICATION_JSON
            content = """{"showDate":"$date"}"""
        }

    private fun userId(email: String) = checkNotNull(users.findByEmail(email)?.id)

    @Test
    fun `creates a private playlist with the predicted songs and lists the ones not found`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(spotifyId)
        stubSpotifyTracks(spotifyId)
        stubCreatePlaylist("pl-1")

        generate(fan.session, spotifyId).andExpect {
            status { isCreated() }
            jsonPath("$.playlistId") { value("pl-1") }
            jsonPath("$.url") { value("https://open.spotify.com/playlist/pl-1") }
            jsonPath("$.name") { value("Na Grade · Fake Band · 10/11/2026") }
            jsonPath("$.created") { value(true) }
            jsonPath("$.tracks[*].title") { value(contains("Opener", "Hit", "Old Hit", "Closer")) }
            jsonPath("$.tracks[2].originalVersion") { value(true) }
            jsonPath("$.tracks[0].originalVersion") { value(false) }
            jsonPath("$.notFound") { value(contains("Medley A / Medley B")) }
        }

        wiremock.verify(
            postRequestedFor(urlPathEqualTo("/v1/me/playlists"))
                .withHeader("Authorization", equalTo("Bearer fake-access-token"))
                .withRequestBody(
                    equalToJson(
                        """{"name":"Na Grade · Fake Band · 10/11/2026","public":false,
                            "description":"Setlist prevista com base em 3 shows · dados: setlist.fm · Na Grade"}""",
                    ),
                ),
        )
        wiremock.verify(
            postRequestedFor(urlPathEqualTo("/v1/playlists/pl-1/items"))
                .withRequestBody(equalToJson("""{"uris":["spotify:track:t-opener","spotify:track:t-hit","spotify:track:t-old-hit","spotify:track:t-closer"]}""")),
        )
        val saved = checkNotNull(playlists.findByUserIdAndArtistSpotifyIdAndShowDate(userId(fan.email), spotifyId, showDate))
        assertThat(saved.playlistId).isEqualTo("pl-1")
        assertThat(saved.provider).isEqualTo("spotify")
    }

    @Test
    fun `generating again for the same artist and date replaces the songs of the same playlist`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(spotifyId)
        stubSpotifyTracks(spotifyId)
        stubCreatePlaylist("pl-1")
        generate(fan.session, spotifyId).andExpect { status { isCreated() } }
        stubInLibrary("pl-1", true)
        wiremock.stubFor(put(urlPathEqualTo("/v1/playlists/pl-1/items")).willReturn(json("""{"snapshot_id":"snap-2"}""")))

        generate(fan.session, spotifyId).andExpect {
            status { isOk() }
            jsonPath("$.playlistId") { value("pl-1") }
            jsonPath("$.created") { value(false) }
        }

        wiremock.verify(1, postRequestedFor(urlPathEqualTo("/v1/me/playlists")))
        wiremock.verify(
            putRequestedFor(urlPathEqualTo("/v1/playlists/pl-1/items"))
                .withRequestBody(equalToJson("""{"uris":["spotify:track:t-opener","spotify:track:t-hit","spotify:track:t-old-hit","spotify:track:t-closer"]}""")),
        )
    }

    @Test
    fun `a playlist removed from the library is created again and the record is updated`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(spotifyId)
        stubSpotifyTracks(spotifyId)
        playlists.save(GeneratedPlaylist(userId(fan.email), "spotify", spotifyId, showDate, "pl-gone"))
        // "Deleting" in the Spotify app only removes it from the library: the playlist is still there
        stubInLibrary("pl-gone", false)
        stubCreatePlaylist("pl-new")

        generate(fan.session, spotifyId).andExpect {
            status { isCreated() }
            jsonPath("$.playlistId") { value("pl-new") }
        }
        assertThat(playlists.findByUserIdAndArtistSpotifyIdAndShowDate(userId(fan.email), spotifyId, showDate)?.playlistId).isEqualTo("pl-new")
        assertThat(wiremock.findAll(putRequestedFor(urlPathEqualTo("/v1/playlists/pl-gone/items")))).isEmpty()
    }

    @Test
    fun `a playlist that no longer exists is created again`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(spotifyId)
        stubSpotifyTracks(spotifyId)
        playlists.save(GeneratedPlaylist(userId(fan.email), "spotify", spotifyId, showDate, "pl-404"))
        stubInLibrary("pl-404", true)
        wiremock.stubFor(put(urlPathEqualTo("/v1/playlists/pl-404/items")).willReturn(json("""{"error":{"status":404,"message":"Not found."}}""", 404)))
        stubCreatePlaylist("pl-new")

        generate(fan.session, spotifyId).andExpect {
            status { isCreated() }
            jsonPath("$.playlistId") { value("pl-new") }
        }
        assertThat(playlists.findByUserIdAndArtistSpotifyIdAndShowDate(userId(fan.email), spotifyId, showDate)?.playlistId).isEqualTo("pl-new")
    }

    @Test
    fun `a double click creates a single playlist`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(spotifyId)
        stubSpotifyTracks(spotifyId)
        // Slow enough for both requests to be in the middle of the flow at the same time
        wiremock.stubFor(
            post(urlPathEqualTo("/v1/me/playlists"))
                .willReturn(json("""{"id":"pl-1","external_urls":{"spotify":"https://open.spotify.com/playlist/pl-1"}}""", 201).withFixedDelay(1500)),
        )
        wiremock.stubFor(post(urlPathEqualTo("/v1/playlists/pl-1/items")).willReturn(json("""{"snapshot_id":"snap"}""", 201)))
        stubInLibrary("pl-1", true)
        wiremock.stubFor(put(urlPathEqualTo("/v1/playlists/pl-1/items")).willReturn(json("""{"snapshot_id":"snap-2"}""")))
        // Warm up the setlist cache, so both requests reach Spotify together
        mockMvc.mockGet("/artists/$spotifyId/predicted-setlist") { cookie(fan.session) }.andExpect { status { isOk() } }

        val statuses = List(2) { CompletableFuture.supplyAsync { generate(fan.session, spotifyId).andReturn().response.status } }.map { it.join() }

        assertThat(statuses).containsExactlyInAnyOrder(201, 200)
        wiremock.verify(1, postRequestedFor(urlPathEqualTo("/v1/me/playlists")))
    }

    @Test
    fun `a 429 from Spotify is retried once after the Retry-After`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(spotifyId)
        stubSpotifyTracks(spotifyId)
        stubCreatePlaylist("pl-1")
        wiremock.stubFor(
            get(urlPathEqualTo("/v1/search")).withQueryParam("q", containing("Opener")).inScenario("rate").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(json("""{"error":{"status":429}}""", 429).withHeader("Retry-After", "1")).willSetStateTo("retried"),
        )
        wiremock.stubFor(
            get(urlPathEqualTo("/v1/search")).withQueryParam("q", containing("Opener")).inScenario("rate").whenScenarioStateIs("retried")
                .willReturn(json("""{"tracks":{"items":[${track("t-opener", "Opener", spotifyId)}]}}""")),
        )

        generate(fan.session, spotifyId).andExpect {
            status { isCreated() }
            jsonPath("$.tracks[0].title") { value("Opener") }
        }
    }

    @Test
    fun `a second 429 in a row returns 502`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(spotifyId)
        wiremock.stubFor(get(urlPathEqualTo("/v1/search")).willReturn(json("""{"error":{"status":429}}""", 429).withHeader("Retry-After", "1")))

        generate(fan.session, spotifyId).andExpect {
            status { isBadGateway() }
            jsonPath("$.code") { value("provider_unavailable") }
            jsonPath("$.provider") { value("spotify") }
        }
        assertThat(wiremock.findAll(getRequestedFor(urlPathEqualTo("/v1/search")))).hasSize(2)
        assertThat(wiremock.findAll(postRequestedFor(urlPathEqualTo("/v1/me/playlists")))).isEmpty()
    }

    // Manual test of #30: Spotify answered 503 to the searches of Foo Fighters ("/" in a medley, "Late!") and Slayyyter
    // ("$", "."). The query keeps only letters, digits and apostrophes; TrackMatcher still compares the real titles
    @Test
    fun `symbols are left out of the search query, not out of the match`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(
            spotifyId,
            sets = """[{"song":[{"name":"${'$'}tarfucker"},{"name":"Daddy A.F."},{"name":"Medley A / Medley B"},{"name":"Marigold","cover":{"name":"Late!"}}]}]""",
        )
        stubSearch("tarfucker", "Fake Band", track("t-star", "${'$'}tarfucker", spotifyId))
        stubSearch("Daddy A F", "Fake Band", track("t-daddy", "Daddy A.F.", spotifyId))
        stubSearch("Medley A Medley B", "Fake Band")
        stubSearch("Marigold", "Fake Band")
        stubSearch("Marigold", "Late", track("t-marigold", "Marigold", "late-id", "Late!"))
        stubCreatePlaylist("pl-1")

        generate(fan.session, spotifyId).andExpect {
            status { isCreated() }
            jsonPath("$.tracks[*].title") { value(contains("${'$'}tarfucker", "Daddy A.F.", "Marigold")) }
            jsonPath("$.tracks[2].originalVersion") { value(true) }
            jsonPath("$.notFound") { value(contains("Medley A / Medley B")) }
        }
    }

    @Test
    fun `a search that fails on Spotify's side leaves only that song out`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(spotifyId)
        stubSpotifyTracks(spotifyId)
        wiremock.stubFor(get(urlPathEqualTo("/v1/search")).withQueryParam("q", containing("track:\"Hit\"")).willReturn(aResponse().withStatus(503)))
        stubCreatePlaylist("pl-1")

        generate(fan.session, spotifyId).andExpect {
            status { isCreated() }
            jsonPath("$.tracks[*].title") { value(contains("Opener", "Old Hit", "Closer")) }
            jsonPath("$.notFound") { value(contains("Hit", "Medley A / Medley B")) }
        }
    }

    @Test
    fun `every search failing means Spotify is down`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(spotifyId)
        wiremock.stubFor(get(urlPathEqualTo("/v1/search")).willReturn(aResponse().withStatus(503)))

        generate(fan.session, spotifyId).andExpect {
            status { isBadGateway() }
            jsonPath("$.code") { value("provider_unavailable") }
            jsonPath("$.provider") { value("spotify") }
        }
        assertThat(wiremock.findAll(postRequestedFor(urlPathEqualTo("/v1/me/playlists")))).isEmpty()
    }

    @Test
    fun `plan B - without a prediction no playlist is created`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(spotifyId, sets = "[]")

        generate(fan.session, spotifyId).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("no_recent_setlists") }
        }
        assertThat(wiremock.findAll(postRequestedFor(urlPathEqualTo("/v1/me/playlists")))).isEmpty()
    }

    @Test
    fun `no track found on Spotify creates no empty playlist`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(spotifyId)
        wiremock.stubFor(get(urlPathEqualTo("/v1/search")).willReturn(json("""{"tracks":{"items":[]}}""")))

        generate(fan.session, spotifyId).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("no_tracks_found") }
            jsonPath("$.notFound") { value(contains("Opener", "Hit", "Old Hit", "Medley A / Medley B", "Closer")) }
        }
        assertThat(wiremock.findAll(postRequestedFor(urlPathEqualTo("/v1/me/playlists")))).isEmpty()
    }

    @Test
    fun `a connection without the playlist scopes has to be made again`() {
        val email = newEmail()
        val session = sessionFor(email)
        // Connected before #30, without playlist-read-private
        connectSpotify(email, scopes = setOf("user-top-read", "playlist-modify-public", "playlist-modify-private"))
        val spotifyId = newSpotifyId()
        stubBand(spotifyId)

        generate(session, spotifyId).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("provider_reconnect_required") }
        }
        assertThat(accounts.findByUserEmailAndProvider(email, "spotify")).isNull()
        assertThat(wiremock.findAll(getRequestedFor(urlPathEqualTo("/v1/search")))).isEmpty()
    }

    @Test
    fun `the show date is required and must be a date`() {
        val fan = connectedFan()

        for (body in listOf("""{}""", """{"showDate":"10/11/2026"}""")) {
            mockMvc.post("/artists/${newSpotifyId()}/playlist") {
                cookie(fan.session)
                withCsrf()
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("malformed_request") }
            }
        }
    }

    @Test
    fun `needs a login, the CSRF token and a valid Spotify id`() {
        mockMvc.post("/artists/${newSpotifyId()}/playlist") {
            withCsrf()
            contentType = MediaType.APPLICATION_JSON
            content = """{"showDate":"2026-11-10"}"""
        }.andExpect { status { isUnauthorized() } }

        val fan = connectedFan()
        mockMvc.post("/artists/${newSpotifyId()}/playlist") {
            cookie(fan.session)
            contentType = MediaType.APPLICATION_JSON
            content = """{"showDate":"2026-11-10"}"""
        }.andExpect { status { isForbidden() } }

        generate(fan.session, "not-a-spotify-id").andExpect {
            status { isBadRequest() }
            jsonPath("$.fields") { value(contains("spotifyId")) }
        }
    }

    @Test
    fun `the playlist name uses the artist and the show date`() {
        val fan = connectedFan()
        val spotifyId = newSpotifyId()
        stubBand(spotifyId)
        stubSpotifyTracks(spotifyId)
        stubCreatePlaylist("pl-1")

        generate(fan.session, spotifyId, date = "2027-01-05").andExpect {
            status { isCreated() }
            jsonPath("$.name") { value(containsString("05/01/2027")) }
        }
    }

    companion object {
        private const val DEFAULT_SETS =
            """[{"song":[{"name":"Opener"},{"name":"Hit"},{"name":"Old Hit","cover":{"name":"Other Band"}},{"name":"Medley A / Medley B"}]},
                {"encore":1,"song":[{"name":"Closer"}]}]"""

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
