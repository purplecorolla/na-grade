package dev.caio.api.auth

import dev.caio.api.usuario.UsuarioRepository
import dev.caio.api.usuario.normalizarEmail
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class UsuarioDetailsService(private val usuarios: UsuarioRepository) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val usuario = usuarios.findByEmail(normalizarEmail(username))
            ?: throw UsernameNotFoundException("usuário não encontrado")
        return User.withUsername(usuario.email)
            .password(usuario.senhaHash)
            .authorities(emptyList())
            .build()
    }
}
