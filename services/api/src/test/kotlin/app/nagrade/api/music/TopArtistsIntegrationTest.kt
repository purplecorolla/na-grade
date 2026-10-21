package app.nagrade.api.music

import app.nagrade.api.TestcontainersConfig
import app.nagrade.api.connection.ConnectedAccountRepository
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
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
import java.util.UUID
import org.springframework.test.web.servlet.get as mockGet

// WireMock plays the Spotify Web API and accounts service. The connection is saved straight through the
// OAuth2AuthorizedClientService: the browser flow is covered by ConnectSpotifyIntegrationTest
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class TopArtistsIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var authorizedClients: OAuth2AuthorizedClientService

    @Autowired
    lateinit var clientRegistrations: ClientRegistrationRepository

    @Autowired
    lateinit var accounts: ConnectedAccountRepository

    private val password = "fake-password-123"

    @BeforeEach
    fun resetSpotify() {
        spotify.resetAll()
        spotify.stubFor(
            get(urlPathEqualTo("/v1/me/top/artists")).willReturn(
                json(
                    """
                    {"items":[
                      {"id":"fake-artist-1","name":"Fake Band","type":"artist","genres":[],
                       "images":[{"url":"https://img.fake/640.jpg","width":640,"height":640},
                                 {"url":"https://img.fake/160.jpg","width":160,"height":160}]},
                      {"id":"fake-artist-2","name":"No Image Band","type":"artist","images":[]}
                    ],"total":2,"limit":20,"offset":0}
                    """.trimIndent(),
                ),
            ),
        )
    }

    private fun json(body: String, status: Int = 200) =
        aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)

    private fun newEmail() = "fan-${UUID.randomUUID()}@nagrade.dev"

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

    // What the browser flow stores; expiresIn negative means the access token already expired
    private fun connectSpotify(email: String, accessToken: String = "fake-access-token", expiresIn: Duration = Duration.ofHours(1)) {
        val issuedAt = Instant.now().plus(expiresIn).minus(Duration.ofHours(1))
        val token = OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER, accessToken, issuedAt, issuedAt.plus(Duration.ofHours(1)), setOf("user-top-read"),
        )
        authorizedClients.saveAuthorizedClient(
            OAuth2AuthorizedClient(
                checkNotNull(clientRegistrations.findByRegistrationId("spotify")), email, token, OAuth2RefreshToken("fake-refresh-token", null),
            ),
            UsernamePasswordAuthenticationToken.authenticated(email, null, emptyList()),
        )
    }

    private fun topArtists(session: Cookie, period: String? = null): ResultActionsDsl =
        mockMvc.mockGet("/me/top-artists") {
            cookie(session)
            period?.let { param("period", it) }
        }

    @Test
    fun `returns id, name and the widest image of the top artists`() {
        val email = newEmail()
        val session = sessionFor(email)
        connectSpotify(email)

        topArtists(session).andExpect {
            status { isOk() }
            jsonPath("$", hasSize<Any>(2))
            jsonPath("$[0].id") { value("fake-artist-1") }
            jsonPath("$[0].name") { value("Fake Band") }
            jsonPath("$[0].imageUrl") { value("https://img.fake/640.jpg") }
            jsonPath("$[1].imageUrl") { value(nullValue()) }
        }

        spotify.verify(
            getRequestedFor(urlPathEqualTo("/v1/me/top/artists"))
                .withHeader("Authorization", equalTo("Bearer fake-access-token"))
                .withQueryParam("time_range", equalTo("medium_term"))
                .withQueryParam("limit", equalTo("20")),
        )
    }

    @Test
    fun `period maps to the Spotify time range`() {
        val email = newEmail()
        val session = sessionFor(email)
        connectSpotify(email)

        for ((period, timeRange) in listOf("short" to "short_term", "long" to "long_term")) {
            topArtists(session, period).andExpect { status { isOk() } }
            spotify.verify(getRequestedFor(urlPathEqualTo("/v1/me/top/artists")).withQueryParam("time_range", equalTo(timeRange)))
        }
    }

    @Test
    fun `an invalid period returns 400 with the field`() {
        val email = newEmail()
        val session = sessionFor(email)
        connectSpotify(email)

        topArtists(session, "forever").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("validation_failed") }
            jsonPath("$.fields") { value(contains("period")) }
        }
        assertThat(spotify.findAll(getRequestedFor(urlPathEqualTo("/v1/me/top/artists")))).isEmpty()
    }

    @Test
    fun `an expired access token is refreshed before the call and saved`() {
        spotify.stubFor(
            post(urlEqualTo("/api/token")).willReturn(
                json("""{"access_token":"fake-access-token-2","token_type":"Bearer","expires_in":3600,"scope":"user-top-read"}"""),
            ),
        )
        val email = newEmail()
        val session = sessionFor(email)
        connectSpotify(email, expiresIn = Duration.ofMinutes(-5))

        topArtists(session).andExpect { status { isOk() } }

        spotify.verify(
            postRequestedFor(urlEqualTo("/api/token"))
                .withRequestBody(containing("grant_type=refresh_token"))
                .withRequestBody(containing("refresh_token=fake-refresh-token")),
        )
        spotify.verify(getRequestedFor(urlPathEqualTo("/v1/me/top/artists")).withHeader("Authorization", equalTo("Bearer fake-access-token-2")))
        // Spotify sent no new refresh token: the stored one is kept
        val account = checkNotNull(accounts.findByUserEmailAndProvider(email, "spotify"))
        assertThat(account.accessToken).isEqualTo("fake-access-token-2")
        assertThat(account.refreshToken).isEqualTo("fake-refresh-token")
    }

    @Test
    fun `a denied refresh asks to reconnect and removes the connection`() {
        spotify.stubFor(
            post(urlEqualTo("/api/token")).willReturn(json("""{"error":"invalid_grant","error_description":"Refresh token revoked"}""", 400)),
        )
        val email = newEmail()
        val session = sessionFor(email)
        connectSpotify(email, expiresIn = Duration.ofMinutes(-5))

        topArtists(session).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("provider_reconnect_required") }
            jsonPath("$.provider") { value("spotify") }
        }
        assertThat(accounts.findByUserEmailAndProvider(email, "spotify")).isNull()
    }

    @Test
    fun `a token refused by Spotify asks to reconnect and removes the connection`() {
        spotify.stubFor(
            get(urlPathEqualTo("/v1/me/top/artists"))
                .willReturn(json("""{"error":{"status":401,"message":"The access token expired"}}""", 401)),
        )
        val email = newEmail()
        val session = sessionFor(email)
        connectSpotify(email)

        topArtists(session).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("provider_reconnect_required") }
        }
        assertThat(accounts.findByUserEmailAndProvider(email, "spotify")).isNull()
    }

    @Test
    fun `Spotify down or rate limiting returns 502 and keeps the connection`() {
        val email = newEmail()
        val session = sessionFor(email)
        connectSpotify(email)

        for (status in listOf(500, 429)) {
            spotify.stubFor(get(urlPathEqualTo("/v1/me/top/artists")).willReturn(json("""{"error":{"status":$status}}""", status)))

            topArtists(session).andExpect {
                status { isBadGateway() }
                jsonPath("$.code") { value("provider_unavailable") }
            }
        }
        assertThat(accounts.findByUserEmailAndProvider(email, "spotify")).isNotNull()
    }

    @Test
    fun `without a Spotify connection returns 409`() {
        val session = sessionFor(newEmail())

        topArtists(session).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("provider_not_connected") }
            jsonPath("$.provider") { value("spotify") }
        }
    }

    @Test
    fun `without a login returns 401 without a body code`() {
        mockMvc.mockGet("/me/top-artists").andExpect {
            status { isUnauthorized() }
            content { string("") }
        }
    }

    companion object {
        private val spotify = WireMockServer(options().dynamicPort()).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun spotifyUris(registry: DynamicPropertyRegistry) {
            registry.add("spring.security.oauth2.client.provider.spotify.authorization-uri") { "${spotify.baseUrl()}/authorize" }
            registry.add("spring.security.oauth2.client.provider.spotify.token-uri") { "${spotify.baseUrl()}/api/token" }
            registry.add("app.spotify.api-base-url") { "${spotify.baseUrl()}/v1" }
        }

        @JvmStatic
        @AfterAll
        fun stopSpotify() = spotify.stop()
    }
}
