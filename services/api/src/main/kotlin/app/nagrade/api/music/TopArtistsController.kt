package app.nagrade.api.music

import jakarta.validation.constraints.Pattern
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class TopArtistsController(private val musicProvider: MusicProvider) {

    @GetMapping("/me/top-artists")
    fun topArtists(
        @AuthenticationPrincipal principal: UserDetails,
        @RequestParam(defaultValue = "medium") @Pattern(regexp = "short|medium|long") period: String,
    ): List<Artist> = musicProvider.topArtists(principal.username, TimePeriod.valueOf(period.uppercase()), LIMIT)

    private companion object {
        const val LIMIT = 20
    }
}
