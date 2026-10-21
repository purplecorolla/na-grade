package app.nagrade.api.connection

import app.nagrade.api.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// Tokens are stored as TokenCipher output, never as they came from the provider
@Entity
@Table(name = "connected_accounts")
class ConnectedAccount(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    val user: User,

    @Column(nullable = false, updatable = false)
    val provider: String,

    @Column(name = "access_token", nullable = false)
    var accessToken: String,

    @Column(name = "access_token_expires_at")
    var accessTokenExpiresAt: Instant?,

    @Column(name = "refresh_token")
    var refreshToken: String?,

    // Space-separated, as in OAuth2
    @Column(nullable = false)
    var scopes: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
