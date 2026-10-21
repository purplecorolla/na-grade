package dev.caio.api.config

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
        // O DSL Kotlin ainda não tem spa(): cookie XSRF-TOKEN que o front devolve no header X-XSRF-TOKEN
        http.csrf { it.spa().csrfTokenRepository(csrfTokenRepository) }
        http {
            authorizeHttpRequests {
                authorize("/actuator/health", permitAll)
                authorize("/auth/csrf", permitAll)
                authorize("/auth/cadastro", permitAll)
                authorize("/auth/login", permitAll)
                authorize("/error", permitAll)
                authorize(anyRequest, authenticated)
            }
            securityContext {
                this.securityContextRepository = securityContextRepository
            }
            // API JSON: sem login responde 401, em vez de redirecionar para a página de login
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

    // Usada no login: troca o ID da sessão e o token CSRF
    @Bean
    fun sessionAuthenticationStrategy(): SessionAuthenticationStrategy = CompositeSessionAuthenticationStrategy(
        listOf(ChangeSessionIdAuthenticationStrategy(), CsrfAuthenticationStrategy(csrfTokenRepository)),
    )

    // BCrypt por padrão; o prefixo {bcrypt} no hash permite trocar de algoritmo sem invalidar senhas antigas
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun authenticationManager(userDetailsService: UserDetailsService, passwordEncoder: PasswordEncoder): AuthenticationManager =
        ProviderManager(DaoAuthenticationProvider(userDetailsService).apply { setPasswordEncoder(passwordEncoder) })
}
