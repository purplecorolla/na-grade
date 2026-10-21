package app.nagrade.api.connection

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ConnectedAccountRepository : JpaRepository<ConnectedAccount, UUID> {
    fun findByUserEmailAndProvider(email: String, provider: String): ConnectedAccount?
    fun findByUserEmailOrderByProvider(email: String): List<ConnectedAccount>

    @Modifying
    @Query("delete from ConnectedAccount a where a.user.email = :email and a.provider = :provider")
    fun deleteByUserEmailAndProvider(email: String, provider: String): Int
}
