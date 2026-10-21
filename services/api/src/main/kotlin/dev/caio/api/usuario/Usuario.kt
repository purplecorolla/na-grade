package dev.caio.api.usuario

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// class comum (não data class): equals/hashCode gerados conflitam com os proxies do Hibernate
@Entity
@Table(name = "usuarios")
class Usuario(
    @Column(nullable = false, unique = true)
    val email: String,

    @Column(name = "senha_hash", nullable = false)
    val senhaHash: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null

    @Column(name = "criado_em", nullable = false, updatable = false)
    val criadoEm: Instant = Instant.now()
}

fun normalizarEmail(email: String): String = email.trim().lowercase()
