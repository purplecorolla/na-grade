package app.nagrade.api.error

import app.nagrade.api.auth.EmailAlreadyRegisteredException
import app.nagrade.api.connection.UnknownProviderException
import app.nagrade.api.music.ArtistNotFoundException
import app.nagrade.api.music.ProviderBusyException
import app.nagrade.api.music.ProviderNotConnectedException
import app.nagrade.api.music.ProviderReconnectRequiredException
import app.nagrade.api.music.ProviderUnavailableException
import app.nagrade.api.setlist.SetlistArtistNotFoundException
import app.nagrade.api.setlist.SetlistQuotaExceededException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

// Errors as ProblemDetail (RFC 9457) plus a stable `code` the frontend uses to pick the translated message
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(EmailAlreadyRegisteredException::class)
    fun emailAlreadyRegistered(e: EmailAlreadyRegisteredException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "Email already registered", "email_already_registered")

    @ExceptionHandler(UnknownProviderException::class)
    fun unknownProvider(e: UnknownProviderException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "Unknown provider", "unknown_provider")

    @ExceptionHandler(ProviderNotConnectedException::class)
    fun providerNotConnected(e: ProviderNotConnectedException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "Music provider not connected", "provider_not_connected")
            .apply { setProperty("provider", e.provider) }

    // 401 like a missing session, but with a body: the frontend tells them apart by the code
    @ExceptionHandler(ProviderReconnectRequiredException::class)
    fun providerReconnectRequired(e: ProviderReconnectRequiredException): ProblemDetail =
        problem(HttpStatus.UNAUTHORIZED, "Connect the music provider again", "provider_reconnect_required")
            .apply { setProperty("provider", e.provider) }

    @ExceptionHandler(ProviderUnavailableException::class)
    fun providerUnavailable(e: ProviderUnavailableException): ProblemDetail {
        log.warn("{} call failed: {}", e.provider, e.cause?.message)
        return problem(HttpStatus.BAD_GATEWAY, "Music provider unavailable", "provider_unavailable")
            .apply { setProperty("provider", e.provider) }
    }

    // Same code as above, but 503: nothing failed on the provider side, the request can be retried in a moment
    @ExceptionHandler(ProviderBusyException::class)
    fun providerBusy(e: ProviderBusyException): ProblemDetail =
        problem(HttpStatus.SERVICE_UNAVAILABLE, "Music provider busy, try again", "provider_unavailable")
            .apply { setProperty("provider", e.provider) }

    @ExceptionHandler(ArtistNotFoundException::class)
    fun artistNotFound(e: ArtistNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "Artist not found", "artist_not_found")
            .apply { setProperty("provider", e.provider) }

    // Plan B of the spike: the frontend offers to build the playlist from a search
    @ExceptionHandler(SetlistArtistNotFoundException::class)
    fun setlistArtistNotFound(e: SetlistArtistNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "Artist not found on the setlist source", "setlist_artist_not_found")

    @ExceptionHandler(SetlistQuotaExceededException::class)
    fun setlistQuotaExceeded(e: SetlistQuotaExceededException): ProblemDetail {
        log.warn("{} daily quota exceeded", e.source)
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Setlist source daily quota exceeded", "setlist_quota_exceeded")
    }

    // Same message for unknown email and wrong password, so the response doesn't reveal who has an account
    @ExceptionHandler(AuthenticationException::class)
    fun invalidCredentials(e: AuthenticationException): ProblemDetail =
        problem(HttpStatus.UNAUTHORIZED, "Invalid email or password", "invalid_credentials")

    // Only field names: Bean Validation messages follow the JVM locale
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        ex.body.setProperty("code", "validation_failed")
        ex.body.setProperty("fields", ex.bindingResult.fieldErrors.map { it.field }.distinct().sorted())
        return super.handleMethodArgumentNotValid(ex, headers, status, request)
    }

    // Constraints on @RequestParam (e.g. period=short|medium|long), same shape as body validation
    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        ex.body.setProperty("code", "validation_failed")
        ex.body.setProperty("fields", ex.parameterValidationResults.mapNotNull { it.methodParameter.parameterName }.distinct().sorted())
        return super.handleHandlerMethodValidationException(ex, headers, status, request)
    }

    // Malformed JSON or a missing required field (non-nullable in Kotlin)
    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val body = problem(HttpStatus.BAD_REQUEST, "Malformed request body", "malformed_request")
        return handleExceptionInternal(ex, body, headers, status, request)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }

    private fun problem(status: HttpStatus, detail: String, code: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code) }
}
