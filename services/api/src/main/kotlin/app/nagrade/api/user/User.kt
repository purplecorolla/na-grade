package app.nagrade.api.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// Plain class (not a data class): generated equals/hashCode clash with Hibernate proxies
@Entity
@Table(name = "users")
class User(
    @Column(nullable = false, unique = true)
    val email: String,

    @Column(name = "password_hash", nullable = false)
    val passwordHash: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}

fun normalizeEmail(email: String): String = email.trim().lowercase()
