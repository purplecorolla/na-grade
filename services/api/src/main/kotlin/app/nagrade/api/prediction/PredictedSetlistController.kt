package app.nagrade.api.prediction

import app.nagrade.api.setlist.ArtistSetlistsService
import app.nagrade.api.setlist.MappedArtist
import jakarta.validation.constraints.Pattern
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneOffset

// Same setlists as GET /artists/{spotifyId}/setlists (#28), so the same cache, limits and errors
@RestController
class PredictedSetlistController(private val artistSetlists: ArtistSetlistsService) {

    @GetMapping("/artists/{spotifyId}/predicted-setlist")
    fun predictedSetlist(
        @AuthenticationPrincipal principal: UserDetails,
        @PathVariable @Pattern(regexp = "[0-9A-Za-z]{22}") spotifyId: String,
    ): PredictedSetlist {
        val recent = artistSetlists.recentSetlists(principal.username, spotifyId)
        val prediction = SetlistPredictor.predict(recent.setlists, LocalDate.now(ZoneOffset.UTC))
        return PredictedSetlist(recent.artist, prediction.basedOn, prediction.confidence, prediction.songs, prediction.warning)
    }
}

data class PredictedSetlist(
    val artist: MappedArtist,
    val basedOn: PredictionBasis?,
    val confidence: Confidence?,
    val songs: List<PredictedSong>,
    val warning: String?,
)
