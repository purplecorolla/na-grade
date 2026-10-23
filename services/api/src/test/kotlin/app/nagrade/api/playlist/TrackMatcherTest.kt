package app.nagrade.api.playlist

import app.nagrade.api.music.Track
import app.nagrade.api.music.TrackArtist
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TrackMatcherTest {

    private val band = TrackArtist("band-id", "Fake Band")
    private val tribute = TrackArtist("tribute-id", "Fake Band Tribute")

    private fun track(id: String, name: String, vararg artists: TrackArtist) = Track("spotify:track:$id", name, artists.toList())

    @Test
    fun `prefers the track of the artist with the exact title`() {
        val results = listOf(
            track("t1", "Everlong", tribute),
            track("t2", "Everlong - Live at Wembley Stadium", band),
            track("t3", "Everlong", band),
        )

        assertThat(TrackMatcher.byArtist(results, "Everlong", "band-id")?.uri).isEqualTo("spotify:track:t3")
    }

    @Test
    fun `without the exact title, a version of the same song is accepted`() {
        val results = listOf(track("t1", "Everlong - Live at Wembley Stadium", band), track("t2", "Everlong (Part 2)", band))

        assertThat(TrackMatcher.byArtist(results, "Everlong", "band-id")?.uri).isEqualTo("spotify:track:t1")
    }

    @Test
    fun `accents, apostrophes and case do not matter`() {
        val results = listOf(track("t1", "Dont Stop Me Now", band), track("t2", "Don't Stop Me Now", band))

        assertThat(TrackMatcher.byArtist(results, "don’t stop me now", "band-id")?.uri).isEqualTo("spotify:track:t2")
    }

    @Test
    fun `a track by another artist or with another title is not accepted`() {
        val results = listOf(track("t1", "Everlong", tribute), track("t2", "Everlong (Part 2)", band))

        assertThat(TrackMatcher.byArtist(results, "Everlong", "band-id")).isNull()
    }

    @Test
    fun `a featured artist still counts`() {
        val results = listOf(track("t1", "Collab Song", TrackArtist("other-id", "Other"), band))

        assertThat(TrackMatcher.byArtist(results, "Collab Song", "band-id")?.uri).isEqualTo("spotify:track:t1")
    }

    @Test
    fun `the original version of a cover is found by the name of the original artist`() {
        val original = TrackArtist("original-id", "Late!")
        val results = listOf(track("t1", "Marigold", TrackArtist("x", "Late Night Band")), track("t2", "Marigold", original))

        assertThat(TrackMatcher.byArtistName(results, "Marigold", "late!")?.uri).isEqualTo("spotify:track:t2")
    }

    @Test
    fun `the original version needs the exact title, without dropping version suffixes`() {
        // Without the artist id the match is weaker, so the title is strict (planning chat)
        val results = listOf(track("t1", "Marigold - Remastered", TrackArtist("original-id", "Late!")))

        assertThat(TrackMatcher.byArtistName(results, "Marigold", "Late!")).isNull()
    }
}
