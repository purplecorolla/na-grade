package app.nagrade.api.playlist

import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
class PlaylistController(private val playlists: PlaylistService) {

    // 201 when the playlist was created, 200 when the existing one was updated
    @PostMapping("/artists/{spotifyId}/playlist")
    fun generate(
        @AuthenticationPrincipal principal: UserDetails,
        @PathVariable @Pattern(regexp = "[0-9A-Za-z]{22}") spotifyId: String,
        @RequestBody request: PlaylistRequest,
    ): ResponseEntity<GeneratedPlaylistResult> {
        val result = playlists.generate(principal.username, spotifyId, request.showDate)
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(result)
    }
}

// The artist and the date identify the show until the show entity exists (Sprint 3)
data class PlaylistRequest(val showDate: LocalDate)
