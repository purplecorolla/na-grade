package app.nagrade.api.auth

import app.nagrade.api.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.util.UUID

// Runs on the real Tomcat: with MockMvc, Spring Session builds the cookie from the MockServletContext
// and ignores server.servlet.session.cookie.*, so the attributes can only be checked here
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig::class)
class SessionCookieIntegrationTest {

    @LocalServerPort
    var port: Int = 0

    private val http = HttpClient.newHttpClient()

    private fun uri(path: String) = URI.create("http://127.0.0.1:$port$path")

    private fun setCookie(response: HttpResponse<*>, name: String): String =
        response.headers().allValues("Set-Cookie").single { it.startsWith("$name=") }

    private fun cookieValue(setCookie: String) = setCookie.substringBefore(";").substringAfter("=")

    private fun post(path: String, xsrf: String, body: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .header("Cookie", "XSRF-TOKEN=$xsrf")
            .header("X-XSRF-TOKEN", xsrf)
            .POST(BodyPublishers.ofString(body))
            .build(),
        BodyHandlers.ofString(),
    )

    @Test
    fun `session cookie is HttpOnly and SameSite Lax`() {
        val csrf = http.send(HttpRequest.newBuilder(uri("/auth/csrf")).build(), BodyHandlers.discarding())
        val xsrf = cookieValue(setCookie(csrf, "XSRF-TOKEN"))
        val body = """{"email":"fan-${UUID.randomUUID()}@nagrade.dev","password":"fake-password-123"}"""

        assertThat(post("/auth/register", xsrf, body).statusCode()).isEqualTo(201)
        val login = post("/auth/login", xsrf, body)

        assertThat(login.statusCode()).isEqualTo(204)
        assertThat(setCookie(login, "SESSION")).contains("HttpOnly").contains("SameSite=Lax")
    }
}
