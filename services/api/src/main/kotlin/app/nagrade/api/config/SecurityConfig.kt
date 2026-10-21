package app.nagrade.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy

@Configuration
class SecurityConfig {

    private val csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse()

    @Bean
    fun filterChain(http: HttpSecurity, securityContextRepository: SecurityContextRepository): SecurityFilterChain {
        // The Kotlin DSL has no spa() yet: XSRF-TOKEN cookie that the frontend sends back in the X-XSRF-TOKEN header
        http.csrf { it.spa().csrfTokenRepository(csrfTokenRepository) }
        http {
            authorizeHttpRequests {
                authorize("/actuator/health", permitAll)
                authorize("/auth/csrf", permitAll)
                authorize("/auth/register", permitAll)
                authorize("/auth/login", permitAll)
                authorize("/error", permitAll)
                authorize(anyRequest, authenticated)
            }
            securityContext {
                this.securityContextRepository = securityContextRepository
            }
            // JSON API: respond 401 when not logged in instead of redirecting to a login page
            exceptionHandling {
                authenticationEntryPoint = HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
            }
            logout {
                logoutUrl = "/auth/logout"
                logoutSuccessHandler = HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)
                deleteCookies("SESSION")
            }
        }
        return http.build()
    }

    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    // Used on login: rotates the session ID and the CSRF token
    @Bean
    fun sessionAuthenticationStrategy(): SessionAuthenticationStrategy = CompositeSessionAuthenticationStrategy(
        listOf(ChangeSessionIdAuthenticationStrategy(), CsrfAuthenticationStrategy(csrfTokenRepository)),
    )

    // BCrypt by default; the {bcrypt} prefix in the hash allows switching algorithms without invalidating old passwords
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun authenticationManager(userDetailsService: UserDetailsService, passwordEncoder: PasswordEncoder): AuthenticationManager =
        ProviderManager(DaoAuthenticationProvider(userDetailsService).apply { setPasswordEncoder(passwordEncoder) })
}
