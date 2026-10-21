package app.nagrade.api.connection

import app.nagrade.api.user.UserRepository
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// Replaces Spring's in-memory default: the connection survives restarts and belongs to the logged-in user.
// The principal name is the user's email (AppUserDetailsService); the registration id is the provider
@Service
@Transactional
class JpaOAuth2AuthorizedClientService(
    private val accounts: ConnectedAccountRepository,
    private val users: UserRepository,
    private val clientRegistrations: ClientRegistrationRepository,
) : OAuth2AuthorizedClientService {

    @Suppress("UNCHECKED_CAST")
    @Transactional(readOnly = true)
    override fun <T : OAuth2AuthorizedClient> loadAuthorizedClient(clientRegistrationId: String, principalName: String): T? {
        val registration = clientRegistrations.findByRegistrationId(clientRegistrationId) ?: return null
        val account = accounts.findByUserEmailAndProvider(principalName, clientRegistrationId) ?: return null
        val accessToken = OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            account.accessToken,
            account.updatedAt,
            account.accessTokenExpiresAt,
            account.scopes.split(" ").filter { it.isNotEmpty() }.toSet(),
        )
        val refreshToken = account.refreshToken?.let { OAuth2RefreshToken(it, null) }
        return OAuth2AuthorizedClient(registration, principalName, accessToken, refreshToken) as T
    }

    override fun saveAuthorizedClient(authorizedClient: OAuth2AuthorizedClient, principal: Authentication) {
        val provider = authorizedClient.clientRegistration.registrationId
        val accessToken = authorizedClient.accessToken
        // Spotify may omit the refresh token when refreshing: keep the one we already have
        val refreshToken = authorizedClient.refreshToken?.tokenValue
        val scopes = accessToken.scopes.sorted().joinToString(" ")

        val existing = accounts.findByUserEmailAndProvider(principal.name, provider)
        if (existing != null) {
            existing.accessToken = accessToken.tokenValue
            existing.accessTokenExpiresAt = accessToken.expiresAt
            existing.refreshToken = refreshToken ?: existing.refreshToken
            existing.scopes = scopes
            existing.updatedAt = accessToken.issuedAt ?: Instant.now()
            return
        }
        val user = checkNotNull(users.findByEmail(principal.name)) { "session user no longer exists" }
        accounts.save(
            ConnectedAccount(user, provider, accessToken.tokenValue, accessToken.expiresAt, refreshToken, scopes).apply {
                updatedAt = accessToken.issuedAt ?: Instant.now()
            },
        )
    }

    override fun removeAuthorizedClient(clientRegistrationId: String, principalName: String) {
        accounts.deleteByUserEmailAndProvider(principalName, clientRegistrationId)
    }
}
