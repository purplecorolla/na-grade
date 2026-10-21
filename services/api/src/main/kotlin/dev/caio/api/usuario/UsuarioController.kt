package dev.caio.api.usuario

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class MeResponse(val id: UUID, val email: String, val criadoEm: Instant)

@RestController
class UsuarioController(private val usuarios: UsuarioRepository) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: UserDetails): MeResponse {
        val usuario = checkNotNull(usuarios.findByEmail(principal.username)) { "usuário da sessão não existe mais" }
        return MeResponse(usuario.id!!, usuario.email, usuario.criadoEm)
    }
}
