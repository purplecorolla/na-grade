package app.nagrade.api.connection

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

// OAuth2AuthorizationCodeGrantFilter handles the provider's callback and then redirects back to this same URI:
// without parameters on success, with ?error= when the exchange failed (e.g. the user denied access).
// A request that still carries `code` here was ignored by the filter: its `state` matched no pending authorization
@RestController
class OAuth2CallbackController(@Value("\${app.connections-return-url}") private val returnUrl: String) {

    @GetMapping("/login/oauth2/code/{provider}")
    fun callback(
        @PathVariable provider: String,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) error: String?,
    ): ResponseEntity<Void> {
        val target = UriComponentsBuilder.fromUriString(returnUrl)
        when {
            error != null -> target.queryParam("error", if (ERROR_CODE.matches(error)) error else "authorization_failed")
            code != null -> target.queryParam("error", "invalid_state")
            else -> target.queryParam("connected", provider)
        }
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, target.encode().build().toUriString())
            .build()
    }

    private companion object {
        // OAuth2 error codes (access_denied, invalid_grant...); anything else is not echoed back
        val ERROR_CODE = Regex("[a-z_]{1,64}")
    }
}
