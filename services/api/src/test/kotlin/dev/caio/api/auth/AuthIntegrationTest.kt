package dev.caio.api.auth

import dev.caio.api.TestcontainersConfig
import dev.caio.api.usuario.UsuarioRepository
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
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

// Os POSTs usam o fluxo SPA real (cookie XSRF-TOKEN + header X-XSRF-TOKEN) em vez do csrf() do
// spring-security-test, que troca o repositório do CsrfFilter por um baseado em sessão para o resto da suíte
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class AuthIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var usuarios: UsuarioRepository

    // E-mail único por teste: o banco é compartilhado entre os testes
    private fun novoEmail() = "fa-${UUID.randomUUID()}@nagrade.dev"

    private val senha = "senha-falsa-123"

    private fun json(email: String, senha: String) = """{"email":"$email","senha":"$senha"}"""

    private fun tokenCsrf(): Cookie = checkNotNull(
        mockMvc.get("/auth/csrf").andExpect { status { isNoContent() } }.andReturn().response.getCookie("XSRF-TOKEN"),
    )

    private fun MockHttpServletRequestDsl.comCsrf(vararg outrosCookies: Cookie) {
        val xsrf = tokenCsrf()
        cookie(xsrf, *outrosCookies)
        header("X-XSRF-TOKEN", xsrf.value)
    }

    private fun cadastrar(email: String, senha: String = this.senha) =
        mockMvc.post("/auth/cadastro") {
            comCsrf()
            contentType = MediaType.APPLICATION_JSON
            content = json(email, senha)
        }

    private fun login(email: String, senha: String = this.senha) =
        mockMvc.post("/auth/login") {
            comCsrf()
            contentType = MediaType.APPLICATION_JSON
            content = json(email, senha)
        }

    private fun sessaoDe(email: String): Cookie {
        cadastrar(email).andExpect { status { isCreated() } }
        return checkNotNull(login(email).andExpect { status { isNoContent() } }.andReturn().response.getCookie("SESSION"))
    }

    @Test
    fun `cadastro grava o e-mail em minúsculas e a senha com BCrypt`() {
        val email = novoEmail()

        cadastrar(email.uppercase()).andExpect { status { isCreated() } }

        val usuario = checkNotNull(usuarios.findByEmail(email))
        assertThat(usuario.senhaHash).startsWith("{bcrypt}").doesNotContain(senha)
    }

    @Test
    fun `cadastro com e-mail repetido retorna 409`() {
        val email = novoEmail()
        cadastrar(email).andExpect { status { isCreated() } }

        cadastrar(email.uppercase()).andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("E-mail já cadastrado") }
        }
    }

    @Test
    fun `cadastro com e-mail inválido ou senha curta retorna 400`() {
        cadastrar("nao-e-email").andExpect { status { isBadRequest() } }
        cadastrar(novoEmail(), "curta").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `POST sem token CSRF retorna 403`() {
        mockMvc.post("/auth/cadastro") {
            contentType = MediaType.APPLICATION_JSON
            content = json(novoEmail(), senha)
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `login com senha errada ou e-mail inexistente retorna 401 com a mesma mensagem`() {
        val email = novoEmail()
        cadastrar(email).andExpect { status { isCreated() } }

        for (tentativa in listOf(login(email, "senha-errada-123"), login(novoEmail()))) {
            tentativa.andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("E-mail ou senha inválidos") }
            }
        }
    }

    @Test
    fun `login aceita o e-mail com maiúsculas e espaços`() {
        val email = novoEmail()
        cadastrar(email).andExpect { status { isCreated() } }

        login("  ${email.uppercase()} ").andExpect { status { isNoContent() } }
    }

    @Test
    fun `me sem sessão retorna 401`() {
        mockMvc.get("/me").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `me com sessão retorna o usuário logado`() {
        val email = novoEmail()
        val sessao = sessaoDe(email)

        mockMvc.get("/me") { cookie(sessao) }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value(email) }
            jsonPath("$.id") { exists() }
            jsonPath("$.senhaHash") { doesNotExist() }
        }
    }

    @Test
    fun `logout invalida a sessão`() {
        val sessao = sessaoDe(novoEmail())

        mockMvc.post("/auth/logout") { comCsrf(sessao) }.andExpect { status { isNoContent() } }

        mockMvc.get("/me") { cookie(sessao) }.andExpect { status { isUnauthorized() } }
    }
}
