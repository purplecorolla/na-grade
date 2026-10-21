package app.nagrade.api.error

import app.nagrade.api.auth.EmailAlreadyRegisteredException
import app.nagrade.api.connection.UnknownProviderException
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

    private fun problem(status: HttpStatus, detail: String, code: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code) }
}
