package app.nagrade.api.setlist

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// Spotify artist -> setlist.fm artist, shared by all users. Saved after the first identical-name match
@Entity
@Table(name = "setlist_artists")
class ArtistMapping(
    @Id
    @Column(name = "spotify_artist_id")
    val spotifyArtistId: String,

    @Column(nullable = false)
    val name: String,

    @Column(name = "setlistfm_mbid", nullable = false)
    val setlistfmMbid: String,
) {
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
