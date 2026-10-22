package app.nagrade.api.prediction

import app.nagrade.api.setlist.Setlist
import app.nagrade.api.setlist.SetlistSet
import app.nagrade.api.setlist.Song
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate

// Pure business rule: no Spring, no HTTP. Each test builds the shows it needs; the dates are relative to `today`
class SetlistPredictorTest {

    private val today = LocalDate.of(2026, 10, 22)

    private var nextId = 0

    private fun show(daysAgo: Long, songs: List<String>, encore: List<String> = emptyList(), tapes: List<String> = emptyList()) = Setlist(
        id = "show-${nextId++}",
        eventDate = today.minusDays(daysAgo),
        venue = "Fake Venue",
        city = "Fake City",
        country = "Fake Country",
        tour = null,
        sets = listOfNotNull(
            SetlistSet(null, false, tapes.map { Song(it, tape = true, coverOf = null) } + songs.map { song(it) }),
            encore.takeIf { it.isNotEmpty() }?.let { SetlistSet(null, true, it.map(::song)) },
        ),
    )

    // "Name|Original Artist" marks a cover
    private fun song(spec: String) = spec.split("|").let { Song(it[0], tape = false, coverOf = it.getOrNull(1)) }

    // Ten songs that every regular show of these tests plays, so the median size is about 10
    private val core = (1..10).map { "Core $it" }

    private fun predict(vararg shows: Setlist) = SetlistPredictor.predict(shows.toList(), today)

    private fun Prediction.titles() = songs.map { it.title }

    @Test
    fun `percentage is the share of shows with the song and the cut is 30 percent`() {
        // 10 regular shows: "Thirty" in 3 of them (30%, in), "Twenty" in 2 (20%, out)
        val shows = (1..10).map { i ->
            show(daysAgo = i.toLong(), songs = core + listOfNotNull("Thirty".takeIf { i <= 3 }, "Twenty".takeIf { i <= 2 }))
        }

        val prediction = predict(*shows.toTypedArray())

        assertThat(prediction.songs.first { it.title == "Core 1" }.percentage).isEqualTo(100)
        assertThat(prediction.songs.first { it.title == "Thirty" }.percentage).isEqualTo(30)
        assertThat(prediction.titles()).doesNotContain("Twenty")
    }

    @Test
    fun `songs are ordered by the median of their position`() {
        val prediction = predict(
            show(1, listOf("B", "A", "C") + core),
            show(2, listOf("A", "B", "C") + core),
            show(3, listOf("A", "C", "B") + core),
        )

        // A: positions 2,1,1 → 1. B: 1,2,3 → 2. C: 3,3,2 → 3
        assertThat(prediction.titles().take(3)).containsExactly("A", "B", "C")
        assertThat(prediction.songs.first().typicalPosition).isEqualTo(1)
    }

    @Test
    fun `encore songs go to the end even with an earlier position`() {
        val prediction = predict(
            show(1, core, encore = listOf("Encore Hit")),
            show(2, core, encore = listOf("Encore Hit")),
            // Played once in the main set: still an encore song, it was in the encore in most shows
            show(3, listOf("Encore Hit") + core),
        )

        assertThat(prediction.titles().last()).isEqualTo("Encore Hit")
        assertThat(prediction.songs.last().encore).isTrue()
        assertThat(prediction.songs.first().encore).isFalse()
    }

    @Test
    fun `tapes are ignored and do not count for positions`() {
        val prediction = predict(
            show(1, core, tapes = listOf("Intro Tape")),
            show(2, core, tapes = listOf("Intro Tape")),
        )

        assertThat(prediction.titles()).doesNotContain("Intro Tape")
        assertThat(prediction.songs.first { it.title == "Core 1" }.typicalPosition).isEqualTo(1)
    }

    @Test
    fun `covers are marked with the original artist`() {
        val prediction = predict(
            show(1, core + "Old Hit|Other Band"),
            show(2, core + "Old Hit|Other Band"),
        )

        assertThat(prediction.songs.first { it.title == "Old Hit" }.coverOf).isEqualTo("Other Band")
        assertThat(prediction.songs.first { it.title == "Core 1" }.coverOf).isNull()
    }

    @Test
    fun `a show between 30 and 70 percent of the median size is a festival set and weighs half`() {
        // Median size 11. The festival plays 5 songs (45%): weight 0.5
        val festival = show(1, core.take(4) + "Festival Only")
        val prediction = predict(
            festival,
            show(2, core + "Regular"),
            show(3, core + "Regular"),
            show(4, core + "Regular"),
        )

        assertThat(prediction.basedOn?.shows).isEqualTo(4)
        assertThat(prediction.basedOn?.weightedShows).isEqualTo(3.5)
        // 0.5 of 3.5 = 14%: out. "Regular": 3 of 3.5 = 86%
        assertThat(prediction.titles()).doesNotContain("Festival Only")
        assertThat(prediction.songs.first { it.title == "Regular" }.percentage).isEqualTo(86)
    }

    @Test
    fun `a show under 30 percent of the median size is incomplete and ignored`() {
        val prediction = predict(
            show(1, listOf("Lonely Song")),
            show(2, core),
            show(3, core),
        )

        assertThat(prediction.basedOn?.shows).isEqualTo(2)
        assertThat(prediction.titles()).doesNotContain("Lonely Song")
    }

    @Test
    fun `future, empty and older than 6 months shows are ignored`() {
        val future = show(-2, listOf("Future Song") + core)
        val empty = show(1, emptyList())
        val old = Setlist("old", today.minusMonths(6).minusDays(1), null, null, null, null, listOf(SetlistSet(null, false, (core + "Old Song").map(::song))))

        val prediction = predict(future, empty, show(3, core), old)

        assertThat(prediction.basedOn?.shows).isEqualTo(1)
        assertThat(prediction.titles()).doesNotContain("Future Song", "Old Song")
    }

    @Test
    fun `only the 10 most recent valid shows are used`() {
        val recent = (1..10).map { show(it.toLong(), core) }
        val eleventh = show(20, core + "Eleventh Only")

        val prediction = predict(*(recent + eleventh).toTypedArray())

        assertThat(prediction.basedOn?.shows).isEqualTo(10)
        assertThat(prediction.basedOn?.from).isEqualTo(today.minusDays(10))
        assertThat(prediction.basedOn?.to).isEqualTo(today.minusDays(1))
        assertThat(prediction.titles()).doesNotContain("Eleventh Only")
    }

    @Test
    fun `variants with remix, live and similar suffixes are grouped under the most frequent name`() {
        val prediction = predict(
            show(1, listOf("Fooley") + core),
            show(2, listOf("Fooley") + core),
            show(3, listOf("FOOLEY (Live at Home)") + core),
            show(4, listOf("Fooley (LEECH Ambient Queen Remix)") + core),
        )

        val fooley = prediction.songs.first()
        assertThat(fooley.title).isEqualTo("Fooley")
        assertThat(fooley.percentage).isEqualTo(100)
        assertThat(fooley.variants).containsExactlyInAnyOrder("FOOLEY (Live at Home)", "Fooley (LEECH Ambient Queen Remix)")
    }

    @Test
    fun `other parentheses are a different song and the curly apostrophe is the same as the straight one`() {
        val prediction = predict(
            show(1, listOf("Epic (Part 1)", "Epic (Part 2)", "don’t stop") + core),
            show(2, listOf("Epic (Part 1)", "Epic (Part 2)", "don't stop") + core),
        )

        assertThat(prediction.titles()).contains("Epic (Part 1)", "Epic (Part 2)")
        assertThat(prediction.songs.filter { it.title.startsWith("don") }).singleElement()
            .satisfies({ assertThat(it.percentage).isEqualTo(100) })
    }

    @Test
    fun `confidence depends on the number of valid shows`() {
        fun confidenceWith(count: Int) = predict(*(1..count).map { show(it.toLong(), core) }.toTypedArray()).confidence

        assertThat(confidenceWith(1)).isEqualTo(Confidence.LOW)
        assertThat(confidenceWith(2)).isEqualTo(Confidence.LOW)
        assertThat(confidenceWith(3)).isEqualTo(Confidence.MEDIUM)
        assertThat(confidenceWith(4)).isEqualTo(Confidence.MEDIUM)
        assertThat(confidenceWith(5)).isEqualTo(Confidence.HIGH)
    }

    @Test
    fun `plan B - without a valid recent show there is a warning and no songs`() {
        val prediction = predict(show(-1, core), show(1, emptyList()))

        assertThat(prediction.songs).isEmpty()
        assertThat(prediction.basedOn).isNull()
        assertThat(prediction.confidence).isNull()
        assertThat(prediction.warning).isEqualTo(Prediction.NO_RECENT_SETLISTS)
    }

    @Test
    fun `weighted shows keep one decimal`() {
        val prediction = predict(show(1, core.take(5)), show(2, core), show(3, core))

        assertThat(prediction.basedOn?.weightedShows).isCloseTo(2.5, within(0.001))
    }
}
