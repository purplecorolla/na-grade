package app.nagrade.api.prediction

import app.nagrade.api.setlist.Setlist
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate

// Acceptance test with real data: the setlists of Kelela as the api returned them in the manual test of #28.
// Of the 20 shows, 4 are in the future, 9 are empty and Brooklyn has a single song (incomplete): 6 are valid
class KelelaAcceptanceTest {

    private data class Fixture(val setlists: List<Setlist>)

    private val setlists = jacksonObjectMapper()
        .readValue<Fixture>(checkNotNull(javaClass.getResource("/fixtures/kelela-setlists.json")).readText())
        .setlists

    private val prediction = SetlistPredictor.predict(setlists, today = LocalDate.of(2026, 10, 22))

    private fun song(title: String) = prediction.songs.single { it.title == title }

    @Test
    fun `uses the 6 valid shows with high confidence`() {
        assertThat(prediction.basedOn?.shows).isEqualTo(6)
        assertThat(prediction.basedOn?.weightedShows).isEqualTo(6.0)
        assertThat(prediction.basedOn?.from).isEqualTo(LocalDate.of(2026, 9, 21))
        assertThat(prediction.basedOn?.to).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(prediction.confidence).isEqualTo(Confidence.HIGH)
        assertThat(prediction.warning).isNull()
    }

    @Test
    fun `Fooley opens in every show and its Remix is listed as a variant`() {
        val fooley = prediction.songs.first()
        assertThat(fooley.title).isEqualTo("Fooley")
        assertThat(fooley.percentage).isEqualTo(100)
        assertThat(fooley.typicalPosition).isEqualTo(1)
        assertThat(fooley.variants).containsExactly("Fooley (LEECH Ambient Queen Remix)")
    }

    @Test
    fun `Send Me Out and Bank Head are in 5 of the 6 shows`() {
        assertThat(song("Send Me Out").percentage).isEqualTo(83)
        assertThat(song("Bank Head").percentage).isEqualTo(83)
    }

    @Test
    fun `new life forms, in 1 of the 6 shows, and Holier, from the incomplete show, are out`() {
        assertThat(prediction.songs.map { it.title }).doesNotContain("new life forms", "Holier")
    }

    @Test
    fun `the encore closes the prediction`() {
        assertThat(prediction.songs.takeLast(2).map { it.title }).containsExactly("if we meet again", "Contact")
        assertThat(prediction.songs.takeLast(2)).allSatisfy { assertThat(it.encore).isTrue() }
        assertThat(prediction.songs.dropLast(2)).allSatisfy { assertThat(it.encore).isFalse() }
    }

    @Test
    fun `the main set follows the order of the shows`() {
        assertThat(prediction.songs.map { it.title }).containsExactly(
            "Fooley", "goin down", "idea 1", "Happy Ending", "Rewind", "against me", "crystalize", "A Message",
            "Send Me Out", "the bridge", "Bank Head", "All the Way Down", "outta time", "don’t piss me off",
            "Turn to Dust", "The High", "Sorbet", "linknb", "if we meet again", "Contact",
        )
    }
}
