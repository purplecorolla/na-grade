package app.nagrade.api.connection

import app.nagrade.api.TestcontainersConfig
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

// WireMock plays Spotify's accounts service. The browser side (redirects to Spotify and back) is driven by hand:
// MockMvc doesn't follow redirects, so each hop is asserted
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class ConnectSpotifyIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var accounts: ConnectedAccountRepository

    @Autowired
    lateinit var authorizedClients: OAuth2AuthorizedClientService

    @Autowired
    lateinit var clientRegistrations: ClientRegistrationRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val password = "fake-password-123"

    @BeforeEach
    fun resetSpotify() {
        spotify.resetAll()
        spotify.stubFor(
            post(urlEqualTo("/api/token")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"access_token":"fake-access-token","token_type":"Bearer","expires_in":3600,
                         "refresh_token":"fake-refresh-token",
                         "scope":"user-top-read playlist-modify-public playlist-modify-private"}
                        """.trimIndent(),
                    ),
            ),
        )
    }

    private fun newEmail() = "fan-${UUID.randomUUID()}@nagrade.dev"

    private fun csrfToken(): Cookie = checkNotNull(
        mockMvc.get("/auth/csrf").andExpect { status { isNoContent() } }.andReturn().response.getCookie("XSRF-TOKEN"),
    )

    private fun MockHttpServletRequestDsl.withCsrf(vararg otherCookies: Cookie) {
        val xsrf = csrfToken()
        cookie(xsrf, *otherCookies)
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

    // "Connect Spotify": returns the query parameters of the redirect to Spotify's consent page
    private fun startConnection(session: Cookie): Map<String, String> {
        val location = mockMvc.get("/oauth2/authorization/spotify") { cookie(session) }
            .andExpect { status { is3xxRedirection() } }
            .andReturn().response.redirectedUrl!!
        assertThat(location).startsWith("${spotify.baseUrl()}/authorize?")
        return UriComponentsBuilder.fromUriString(location).build().queryParams
            .mapValues { (_, values) -> URLDecoder.decode(values.single(), Charsets.UTF_8) }
    }

    // Spotify redirects the browser to the callback; the api redirects to itself and then to the return URL
    private fun callback(session: Cookie, vararg params: Pair<String, String>): String {
        val first = mockMvc.get("/login/oauth2/code/spotify") {
            cookie(session)
            params.forEach { (name, value) -> param(name, value) }
        }.andExpect { status { is3xxRedirection() } }.andReturn().response.redirectedUrl!!
        if (!first.contains("/login/oauth2/code/spotify")) return first
        return mockMvc.get(first) { cookie(session) }
            .andExpect { status { is3xxRedirection() } }
            .andReturn().response.redirectedUrl!!
    }

    private fun connect(session: Cookie): String {
        val authorization = startConnection(session)
        return callback(session, "code" to "fake-code", "state" to authorization.getValue("state"))
    }

    @Test
    fun `connect sends PKCE, exchanges the code with the client secret and links the account to the user`() {
        val email = newEmail()
        val session = sessionFor(email)

        val authorization = startConnection(session)
        assertThat(authorization).containsEntry("response_type", "code")
            .containsEntry("client_id", "fake-client-id")
            .containsEntry("redirect_uri", "http://localhost/login/oauth2/code/spotify")
            .containsEntry("code_challenge_method", "S256")
            .containsKeys("state", "code_challenge")
        assertThat(authorization.getValue("scope").split(" "))
            .containsExactlyInAnyOrder("user-top-read", "playlist-modify-public", "playlist-modify-private", "playlist-read-private")

        val returnUrl = callback(session, "code" to "fake-code", "state" to authorization.getValue("state"))
        assertThat(returnUrl).isEqualTo("/me/connections?connected=spotify")

        val tokenRequest = spotify.findAll(postRequestedFor(urlEqualTo("/api/token"))).single()
        val form = tokenRequest.bodyAsString.split("&").associate {
            val (name, value) = it.split("=", limit = 2)
            name to URLDecoder.decode(value, Charsets.UTF_8)
        }
        assertThat(form).containsEntry("grant_type", "authorization_code").containsEntry("code", "fake-code")
        // Proves we hold the verifier behind the challenge sent in the first redirect
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(form.getValue("code_verifier").toByteArray()))
        assertThat(challenge).isEqualTo(authorization.getValue("code_challenge"))
        val basic = Base64.getEncoder().encodeToString("fake-client-id:fake-client-secret".toByteArray())
        assertThat(tokenRequest.getHeader("Authorization")).isEqualTo("Basic $basic")

        val account = checkNotNull(accounts.findByUserEmailAndProvider(email, "spotify"))
        assertThat(account.refreshToken).isEqualTo("fake-refresh-token")
        assertThat(account.accessTokenExpiresAt).isNotNull()
    }

    @Test
    fun `the database only holds encrypted tokens`() {
        val email = newEmail()
        connect(sessionFor(email))

        // Raw SELECT, bypassing the JPA converter: what a database leak would expose
        val row = jdbc.queryForMap(
            """
            SELECT a.access_token, a.refresh_token FROM connected_accounts a
            JOIN users u ON u.id = a.user_id WHERE u.email = ?
            """.trimIndent(),
            email,
        )
        assertThat(row["access_token"] as String).startsWith("v1:").doesNotContain("fake-access-token")
        assertThat(row["refresh_token"] as String).startsWith("v1:").doesNotContain("fake-refresh-token")
        // Through JPA the tokens come back decrypted
        val account = checkNotNull(accounts.findByUserEmailAndProvider(email, "spotify"))
        assertThat(account.accessToken).isEqualTo("fake-access-token")
    }

    @Test
    fun `connections list the provider and scopes without the tokens`() {
        val session = sessionFor(newEmail())
        connect(session)

        mockMvc.get("/me/connections") { cookie(session) }.andExpect {
            status { isOk() }
            jsonPath("$", hasSize<Any>(1))
            jsonPath("$[0].provider") { value("spotify") }
            jsonPath("$[0].scopes") {
                value(containsInAnyOrder("user-top-read", "playlist-modify-public", "playlist-modify-private"))
            }
            jsonPath("$[0].connectedAt") { exists() }
            jsonPath("$[0].accessToken") { doesNotExist() }
            jsonPath("$[0].refreshToken") { doesNotExist() }
        }
    }

    @Test
    fun `disconnect forgets the tokens and is idempotent`() {
        val email = newEmail()
        val session = sessionFor(email)
        connect(session)

        repeat(2) {
            mockMvc.delete("/me/connections/spotify") { withCsrf(session) }.andExpect { status { isNoContent() } }
        }

        assertThat(accounts.findByUserEmailAndProvider(email, "spotify")).isNull()
        mockMvc.get("/me/connections") { cookie(session) }.andExpect { jsonPath("$", hasSize<Any>(0)) }
    }

    @Test
    fun `disconnect an unknown provider returns 404`() {
        val session = sessionFor(newEmail())

        mockMvc.delete("/me/connections/deezer") { withCsrf(session) }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("unknown_provider") }
        }
    }

    @Test
    fun `connecting again keeps a single connection per user and provider`() {
        val email = newEmail()
        val session = sessionFor(email)

        connect(session)
        connect(session)

        assertThat(accounts.findByUserEmailOrderByProvider(email)).hasSize(1)
    }

    @Test
    fun `saving a refreshed token without a refresh token keeps the stored one`() {
        val email = newEmail()
        val session = sessionFor(email)
        connect(session)
        val now = Instant.now()
        val refreshed = OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER, "fake-access-token-2", now, now.plusSeconds(3600), setOf("user-top-read"),
        )
        val registration = checkNotNull(clientRegistrations.findByRegistrationId("spotify"))

        authorizedClients.saveAuthorizedClient(
            OAuth2AuthorizedClient(registration, email, refreshed, null),
            UsernamePasswordAuthenticationToken.authenticated(email, null, emptyList()),
        )

        val loaded = checkNotNull(authorizedClients.loadAuthorizedClient<OAuth2AuthorizedClient>("spotify", email))
        assertThat(loaded.accessToken.tokenValue).isEqualTo("fake-access-token-2")
        assertThat(loaded.refreshToken?.tokenValue).isEqualTo("fake-refresh-token")
    }

    @Test
    fun `user denies access on Spotify`() {
        val email = newEmail()
        val session = sessionFor(email)
        val authorization = startConnection(session)

        val returnUrl = callback(session, "error" to "access_denied", "state" to authorization.getValue("state"))

        assertThat(returnUrl).isEqualTo("/me/connections?error=access_denied")
        assertThat(spotify.findAll(postRequestedFor(urlEqualTo("/api/token")))).isEmpty()
        assertThat(accounts.findByUserEmailAndProvider(email, "spotify")).isNull()
    }

    @Test
    fun `callback with a state that matches no pending authorization is rejected`() {
        val email = newEmail()
        val session = sessionFor(email)
        startConnection(session)

        val returnUrl = callback(session, "code" to "fake-code", "state" to "forged-state")

        assertThat(returnUrl).isEqualTo("/me/connections?error=invalid_state")
        assertThat(spotify.findAll(postRequestedFor(urlEqualTo("/api/token")))).isEmpty()
        assertThat(accounts.findByUserEmailAndProvider(email, "spotify")).isNull()
    }

    @Test
    fun `token exchange failure comes back as an error`() {
        spotify.stubFor(
            post(urlEqualTo("/api/token")).withRequestBody(containing("code=expired-code")).willReturn(
                aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                    .withBody("""{"error":"invalid_grant","error_description":"Invalid authorization code"}"""),
            ),
        )
        val email = newEmail()
        val session = sessionFor(email)
        val authorization = startConnection(session)

        val returnUrl = callback(session, "code" to "expired-code", "state" to authorization.getValue("state"))

        assertThat(returnUrl).isEqualTo("/me/connections?error=invalid_grant")
        assertThat(accounts.findByUserEmailAndProvider(email, "spotify")).isNull()
    }

    @Test
    fun `connect without a login returns 401 and never reaches Spotify`() {
        mockMvc.get("/oauth2/authorization/spotify").andExpect { status { isUnauthorized() } }
        mockMvc.get("/me/connections").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the playground is not served outside the dev profile`() {
        mockMvc.get("/playground.html").andExpect { status { isNotFound() } }
    }

    companion object {
        private val spotify = WireMockServer(options().dynamicPort()).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun spotifyUris(registry: DynamicPropertyRegistry) {
            registry.add("spring.security.oauth2.client.provider.spotify.authorization-uri") { "${spotify.baseUrl()}/authorize" }
            registry.add("spring.security.oauth2.client.provider.spotify.token-uri") { "${spotify.baseUrl()}/api/token" }
        }

        @JvmStatic
        @AfterAll
        fun stopSpotify() = spotify.stop()
    }
}
