package dev.caio.api.erro

import dev.caio.api.auth.EmailJaCadastradoException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

// Erros no formato ProblemDetail (RFC 9457); a classe base já cobre 400 de validação e JSON inválido
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(EmailJaCadastradoException::class)
    fun emailJaCadastrado(e: EmailJaCadastradoException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "E-mail já cadastrado")

    // Mesma mensagem para e-mail inexistente e senha errada, para não revelar quem tem conta
    @ExceptionHandler(AuthenticationException::class)
    fun loginInvalido(e: AuthenticationException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos")
}
