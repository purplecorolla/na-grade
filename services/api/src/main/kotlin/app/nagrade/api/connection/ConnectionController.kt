package app.nagrade.api.connection

import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

class UnknownProviderException(provider: String) : RuntimeException("unknown provider: $provider")

// Never exposes the tokens
data class ConnectionResponse(val provider: String, val scopes: List<String>, val connectedAt: Instant)

@RestController
class ConnectionController(
    private val accounts: ConnectedAccountRepository,
    private val authorizedClients: OAuth2AuthorizedClientService,
    private val clientRegistrations: ClientRegistrationRepository,
) {

    @GetMapping("/me/connections")
    @Transactional(readOnly = true)
    fun list(@AuthenticationPrincipal principal: UserDetails): List<ConnectionResponse> =
        accounts.findByUserEmailOrderByProvider(principal.username).map {
            ConnectionResponse(it.provider, it.scopes.split(" ").filter(String::isNotEmpty), it.createdAt)
        }

    // Only forgets the tokens: Spotify has no revocation API, the user revokes for good at spotify.com/account/apps.
    // Idempotent: disconnecting twice also returns 204
    @DeleteMapping("/me/connections/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disconnect(@AuthenticationPrincipal principal: UserDetails, @PathVariable provider: String) {
        clientRegistrations.findByRegistrationId(provider) ?: throw UnknownProviderException(provider)
        authorizedClients.removeAuthorizedClient(provider, principal.username)
    }
}
