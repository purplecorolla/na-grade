package app.nagrade.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

@Configuration
class OAuth2ClientConfig {

    // The service-based manager works outside an HTTP request (the radar, Sprint 3). Only refresh_token: the
    // authorization code is obtained by the browser flow (SecurityConfig). Its default failure handler removes
    // the connection when the refresh is denied (invalid_grant)
    @Bean
    fun authorizedClientManager(
        clientRegistrations: ClientRegistrationRepository,
        authorizedClients: OAuth2AuthorizedClientService,
    ): OAuth2AuthorizedClientManager =
        AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients).apply {
            setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().refreshToken().build())
        }
}
