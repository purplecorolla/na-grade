package app.nagrade.api.auth

import app.nagrade.api.user.User
import app.nagrade.api.user.UserRepository
import app.nagrade.api.user.normalizeEmail
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

class EmailAlreadyRegisteredException : RuntimeException("email already registered")

@Service
class RegistrationService(
    private val users: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    fun register(email: String, password: String): User {
        val normalized = normalizeEmail(email)
        if (users.existsByEmail(normalized)) throw EmailAlreadyRegisteredException()
        // The UNIQUE constraint covers two concurrent sign-ups that both passed the check above
        return try {
            users.saveAndFlush(User(normalized, checkNotNull(passwordEncoder.encode(password))))
        } catch (e: DataIntegrityViolationException) {
            throw EmailAlreadyRegisteredException()
        }
    }
}
