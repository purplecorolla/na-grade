package app.nagrade.api.setlist

import jakarta.validation.constraints.Pattern
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class ArtistSetlistsController(private val artistSetlists: ArtistSetlistsService) {

    // The id goes into the Spotify URL: only the Spotify format (22 base62 characters) gets through
    @GetMapping("/artists/{spotifyId}/setlists")
    fun setlists(
        @AuthenticationPrincipal principal: UserDetails,
        @PathVariable @Pattern(regexp = "[0-9A-Za-z]{22}") spotifyId: String,
    ): ArtistSetlists = artistSetlists.recentSetlists(principal.username, spotifyId)
}
