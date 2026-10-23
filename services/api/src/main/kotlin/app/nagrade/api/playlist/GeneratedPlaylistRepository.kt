package app.nagrade.api.playlist

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.util.UUID

interface GeneratedPlaylistRepository : JpaRepository<GeneratedPlaylist, UUID> {

    fun findByUserIdAndArtistSpotifyIdAndShowDate(userId: UUID, artistSpotifyId: String, showDate: LocalDate): GeneratedPlaylist?

    // Takes the row of this user, artist and show, or does nothing when it exists. A second transaction doing the
    // same waits here until the first one commits: the unique key turns a double click into an update
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO generated_playlists (user_id, provider, artist_spotify_id, show_date)
            VALUES (:userId, :provider, :artistSpotifyId, :showDate)
            ON CONFLICT (user_id, artist_spotify_id, show_date) DO NOTHING
        """,
    )
    fun reserve(userId: UUID, provider: String, artistSpotifyId: String, showDate: LocalDate): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from GeneratedPlaylist p where p.userId = :userId and p.artistSpotifyId = :artistSpotifyId and p.showDate = :showDate")
    fun lock(userId: UUID, artistSpotifyId: String, showDate: LocalDate): GeneratedPlaylist?
}
