package app.nagrade.api.user

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class MeResponse(val id: UUID, val email: String, val createdAt: Instant)

@RestController
class UserController(private val users: UserRepository) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: UserDetails): MeResponse {
        val user = checkNotNull(users.findByEmail(principal.username)) { "session user no longer exists" }
        return MeResponse(user.id!!, user.email, user.createdAt)
    }
}
