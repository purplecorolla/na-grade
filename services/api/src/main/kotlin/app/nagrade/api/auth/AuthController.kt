package app.nagrade.api.auth

import app.nagrade.api.user.normalizeEmail
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

// BCrypt only uses the first 72 bytes of the password; anything beyond would be silently ignored
data class RegisterRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    @field:Size(min = 8, max = 72) val password: String,
)

data class LoginRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val password: String,
)

@RestController
@RequestMapping("/auth")
class AuthController(
    private val registrationService: RegistrationService,
    private val authenticationManager: AuthenticationManager,
    private val securityContextRepository: SecurityContextRepository,
    private val sessionAuthenticationStrategy: SessionAuthenticationStrategy,
) {

    // Issues the XSRF-TOKEN cookie; the frontend calls it once before the first POST
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun csrf(token: CsrfToken) {
        token.token
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody req: RegisterRequest) {
        registrationService.register(req.email, req.password)
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun login(@Valid @RequestBody req: LoginRequest, request: HttpServletRequest, response: HttpServletResponse) {
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(normalizeEmail(req.email), req.password),
        )
        // Rotates the session ID and the CSRF token (against session fixation) before storing the login
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response)
        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        SecurityContextHolder.setContext(context)
        // Since Spring Security 6, saving the context to the session is explicit
        securityContextRepository.saveContext(context, request, response)
    }
}
