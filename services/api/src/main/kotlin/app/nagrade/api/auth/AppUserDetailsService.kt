package app.nagrade.api.auth

import app.nagrade.api.user.UserRepository
import app.nagrade.api.user.normalizeEmail
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import org.springframework.security.core.userdetails.User as SecurityUser

@Service
class AppUserDetailsService(private val users: UserRepository) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val user = users.findByEmail(normalizeEmail(username))
            ?: throw UsernameNotFoundException("user not found")
        return SecurityUser.withUsername(user.email)
            .password(user.passwordHash)
            .authorities(emptyList())
            .build()
    }
}
