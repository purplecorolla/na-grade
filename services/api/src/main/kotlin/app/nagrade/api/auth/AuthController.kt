package dev.caio.api.auth

import dev.caio.api.usuario.normalizarEmail
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// BCrypt só considera os primeiros 72 bytes da senha; acima disso o resto seria ignorado em silêncio
data class CadastroRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    @field:Size(min = 8, max = 72) val senha: String,
)

data class LoginRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val senha: String,
)

@RestController
@RequestMapping("/auth")
class AuthController(
    private val cadastroService: CadastroService,
    private val authenticationManager: AuthenticationManager,
    private val securityContextRepository: SecurityContextRepository,
    private val sessionAuthenticationStrategy: SessionAuthenticationStrategy,
) {

    // Gera o cookie XSRF-TOKEN; o front chama uma vez antes do primeiro POST
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun csrf(token: CsrfToken) {
        token.token
    }

    @PostMapping("/cadastro")
    @ResponseStatus(HttpStatus.CREATED)
    fun cadastrar(@Valid @RequestBody req: CadastroRequest) {
        cadastroService.cadastrar(req.email, req.senha)
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun login(@Valid @RequestBody req: LoginRequest, request: HttpServletRequest, response: HttpServletResponse) {
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(normalizarEmail(req.email), req.senha),
        )
        // Troca o ID da sessão e o token CSRF (contra session fixation) antes de gravar o login
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response)
        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        SecurityContextHolder.setContext(context)
        // Desde o Spring Security 6, salvar o contexto na sessão é explícito
        securityContextRepository.saveContext(context, request, response)
    }
}
