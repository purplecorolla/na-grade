package app.nagrade.api.connection

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.authentication.AuthenticationTrustResolverImpl
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest

// The /oauth2/authorization/{provider} filter runs before the authorization rules, so anyone could start a
// connection. Returning null lets the request go on, and without a login it gets the usual 401
class LoggedInOnlyAuthorizationRequestResolver(clientRegistrations: ClientRegistrationRepository) :
    OAuth2AuthorizationRequestResolver {

    private val delegate = DefaultOAuth2AuthorizationRequestResolver(clientRegistrations, "/oauth2/authorization")
    private val trustResolver = AuthenticationTrustResolverImpl()

    override fun resolve(request: HttpServletRequest): OAuth2AuthorizationRequest? =
        if (loggedIn()) delegate.resolve(request) else null

    override fun resolve(request: HttpServletRequest, clientRegistrationId: String): OAuth2AuthorizationRequest? =
        if (loggedIn()) delegate.resolve(request, clientRegistrationId) else null

    private fun loggedIn() = trustResolver.isAuthenticated(SecurityContextHolder.getContext().authentication)
}
