package dev.caio.api.auth

import dev.caio.api.TestcontainersConfig
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

// Roda no Tomcat real: no MockMvc o Spring Session monta o cookie a partir do MockServletContext
// e ignora server.servlet.session.cookie.*, então os atributos só podem ser conferidos aqui
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig::class)
class CookieSessaoIntegrationTest {

    @LocalServerPort
    var porta: Int = 0

    private val http = HttpClient.newHttpClient()

    private fun uri(caminho: String) = URI.create("http://127.0.0.1:$porta$caminho")

    private fun setCookie(resposta: HttpResponse<*>, nome: String): String =
        resposta.headers().allValues("Set-Cookie").single { it.startsWith("$nome=") }

    private fun valor(setCookie: String) = setCookie.substringBefore(";").substringAfter("=")

    private fun post(caminho: String, xsrf: String, corpo: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(uri(caminho))
            .header("Content-Type", "application/json")
            .header("Cookie", "XSRF-TOKEN=$xsrf")
            .header("X-XSRF-TOKEN", xsrf)
            .POST(BodyPublishers.ofString(corpo))
            .build(),
        BodyHandlers.ofString(),
    )

    @Test
    fun `cookie de sessão é HttpOnly e SameSite Lax`() {
        val xsrf = valor(setCookie(http.send(HttpRequest.newBuilder(uri("/auth/csrf")).build(), BodyHandlers.discarding()), "XSRF-TOKEN"))
        val corpo = """{"email":"fa-${UUID.randomUUID()}@nagrade.dev","senha":"senha-falsa-123"}"""

        assertThat(post("/auth/cadastro", xsrf, corpo).statusCode()).isEqualTo(201)
        val login = post("/auth/login", xsrf, corpo)

        assertThat(login.statusCode()).isEqualTo(204)
        assertThat(setCookie(login, "SESSION")).contains("HttpOnly").contains("SameSite=Lax")
    }
}
