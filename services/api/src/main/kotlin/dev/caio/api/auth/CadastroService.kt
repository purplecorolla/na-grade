package dev.caio.api.auth

import dev.caio.api.usuario.Usuario
import dev.caio.api.usuario.UsuarioRepository
import dev.caio.api.usuario.normalizarEmail
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

class EmailJaCadastradoException : RuntimeException("e-mail já cadastrado")

@Service
class CadastroService(
    private val usuarios: UsuarioRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    fun cadastrar(email: String, senha: String): Usuario {
        val normalizado = normalizarEmail(email)
        if (usuarios.existsByEmail(normalizado)) throw EmailJaCadastradoException()
        // A constraint UNIQUE cobre dois cadastros simultâneos que passaram juntos pela checagem acima
        return try {
            usuarios.saveAndFlush(Usuario(normalizado, checkNotNull(passwordEncoder.encode(senha))))
        } catch (e: DataIntegrityViolationException) {
            throw EmailJaCadastradoException()
        }
    }
}
