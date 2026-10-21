package app.nagrade.api.auth

import app.nagrade.api.TestcontainersConfig
import app.nagrade.api.user.UserRepository
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

// POSTs use the real SPA flow (XSRF-TOKEN cookie + X-XSRF-TOKEN header) instead of spring-security-test's
// csrf(), which swaps the CsrfFilter repository for a session-based one for the rest of the suite
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class AuthIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var users: UserRepository

    // Unique email per test: the database is shared across tests
    private fun newEmail() = "fan-${UUID.randomUUID()}@nagrade.dev"

    private val password = "fake-password-123"

    private fun json(email: String, password: String) = """{"email":"$email","password":"$password"}"""

    private fun csrfToken(): Cookie = checkNotNull(
        mockMvc.get("/auth/csrf").andExpect { status { isNoContent() } }.andReturn().response.getCookie("XSRF-TOKEN"),
    )

    private fun MockHttpServletRequestDsl.withCsrf(vararg otherCookies: Cookie) {
        val xsrf = csrfToken()
        cookie(xsrf, *otherCookies)
        header("X-XSRF-TOKEN", xsrf.value)
    }

    private fun register(email: String, password: String = this.password) =
        mockMvc.post("/auth/register") {
            withCsrf()
            contentType = MediaType.APPLICATION_JSON
            content = json(email, password)
        }

    private fun login(email: String, password: String = this.password) =
        mockMvc.post("/auth/login") {
            withCsrf()
            contentType = MediaType.APPLICATION_JSON
            content = json(email, password)
        }

    private fun sessionFor(email: String): Cookie {
        register(email).andExpect { status { isCreated() } }
        return checkNotNull(login(email).andExpect { status { isNoContent() } }.andReturn().response.getCookie("SESSION"))
    }

    @Test
    fun `register stores the email lowercased and the password with BCrypt`() {
        val email = newEmail()

        register(email.uppercase()).andExpect { status { isCreated() } }

        val user = checkNotNull(users.findByEmail(email))
        assertThat(user.passwordHash).startsWith("{bcrypt}").doesNotContain(password)
    }

    @Test
    fun `register with a duplicate email returns 409`() {
        val email = newEmail()
        register(email).andExpect { status { isCreated() } }

        register(email.uppercase()).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("email_already_registered") }
        }
    }

    @Test
    fun `register with an invalid email or short password returns 400 with the fields`() {
        register("not-an-email").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("validation_failed") }
            jsonPath("$.fields") { value(contains("email")) }
        }
        register(newEmail(), "short").andExpect {
            status { isBadRequest() }
            jsonPath("$.fields") { value(contains("password")) }
        }
    }

    @Test
    fun `register with a missing field returns 400 malformed_request`() {
        mockMvc.post("/auth/register") {
            withCsrf()
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"${newEmail()}"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("malformed_request") }
        }
    }

    @Test
    fun `POST without a CSRF token returns 403`() {
        mockMvc.post("/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = json(newEmail(), password)
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `login with a wrong password or unknown email returns the same 401`() {
        val email = newEmail()
        register(email).andExpect { status { isCreated() } }

        for (attempt in listOf(login(email, "wrong-password-123"), login(newEmail()))) {
            attempt.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("invalid_credentials") }
                jsonPath("$.detail") { value("Invalid email or password") }
            }
        }
    }

    @Test
    fun `login accepts the email with uppercase letters and surrounding spaces`() {
        val email = newEmail()
        register(email).andExpect { status { isCreated() } }

        login("  ${email.uppercase()} ").andExpect { status { isNoContent() } }
    }

    @Test
    fun `me without a session returns 401`() {
        mockMvc.get("/me").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `me with a session returns the logged-in user`() {
        val email = newEmail()
        val session = sessionFor(email)

        mockMvc.get("/me") { cookie(session) }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value(email) }
            jsonPath("$.id") { exists() }
            jsonPath("$.createdAt") { exists() }
            jsonPath("$.passwordHash") { doesNotExist() }
        }
    }

    @Test
    fun `logout invalidates the session`() {
        val session = sessionFor(newEmail())

        mockMvc.post("/auth/logout") { withCsrf(session) }.andExpect { status { isNoContent() } }

        mockMvc.get("/me") { cookie(session) }.andExpect { status { isUnauthorized() } }
    }
}
