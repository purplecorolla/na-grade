package app.nagrade.api.playlist

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "generated_playlists")
class GeneratedPlaylist(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,

    @Column(nullable = false, updatable = false)
    val provider: String,

    @Column(name = "artist_spotify_id", nullable = false, updatable = false)
    val artistSpotifyId: String,

    @Column(name = "show_date", nullable = false, updatable = false)
    val showDate: LocalDate,

    @Column(name = "playlist_id")
    var playlistId: String?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
