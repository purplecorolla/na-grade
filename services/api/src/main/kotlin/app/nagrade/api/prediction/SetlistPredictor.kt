package app.nagrade.api.prediction

import app.nagrade.api.setlist.Setlist
import com.fasterxml.jackson.annotation.JsonValue
import java.time.LocalDate
import kotlin.math.roundToInt

// Predicted setlist of an artist from their recent shows (#29). Pure business rule: no Spring, no HTTP.
// The rules were decided in the planning chat (comments of #29); SetlistPredictorTest documents each one
object SetlistPredictor {

    const val MAX_SHOWS = 10
    const val WINDOW_MONTHS = 6L

    // Size of a show (songs, without tapes) compared to the median size of the recent shows. The median, not
    // the mean: partial setlists, common on setlist.fm, would pull the mean down
    const val INCOMPLETE_RATIO = 0.3 // below: incomplete setlist, ignored
    const val FESTIVAL_RATIO = 0.7 // from INCOMPLETE_RATIO up to here: festival set, weighs FESTIVAL_WEIGHT
    const val FESTIVAL_WEIGHT = 0.5

    // Weighted share of the shows a song needs to enter the prediction
    const val MIN_SHARE = 0.3

    const val HIGH_CONFIDENCE_SHOWS = 5
    const val MEDIUM_CONFIDENCE_SHOWS = 3

    // Parenthesized suffixes that mark a version of the same song: "Fooley (LEECH Ambient Queen Remix)".
    // Any other parentheses name another song: "Epic (Part 2)"
    private val SUFFIX = Regex("""\s*\(([^()]*)\)\s*$""")
    private val VERSION_WORDS = Regex("""\b(remix|mix|edit|version|live|acoustic|demo)\b""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")
    private const val EPSILON = 1e-9

    fun predict(setlists: List<Setlist>, today: LocalDate): Prediction {
        val candidates = setlists
            .filter { !it.eventDate.isAfter(today) && !it.eventDate.isBefore(today.minusMonths(WINDOW_MONTHS)) }
            .map { Show(it.eventDate, it.playedSongs()) }
            .filter { it.songs.isNotEmpty() }
        if (candidates.isEmpty()) return Prediction.noRecentSetlists()

        val medianSize = candidates.map { it.songs.size.toDouble() }.median()
        val shows = candidates
            .mapNotNull { show -> weightOf(show.songs.size / medianSize)?.let { show.copy(weight = it) } }
            .sortedByDescending { it.date }
            .take(MAX_SHOWS)
        if (shows.isEmpty()) return Prediction.noRecentSetlists()

        val totalWeight = shows.sumOf { it.weight }
        val songs = appearancesBySong(shows).values
            .filter { it.sumOf(Appearance::weight) / totalWeight >= MIN_SHARE - EPSILON }
            .map { predictedSong(it, totalWeight) }
            .sortedWith(
                compareBy<Ranked> { it.song.encore }.thenBy { it.medianRelativePosition }.thenBy { it.song.typicalPosition }
                    .thenByDescending { it.song.percentage }.thenBy { it.song.title },
            )
            .map { it.song }

        return Prediction(
            basedOn = PredictionBasis(shows.size, totalWeight, shows.minOf { it.date }, shows.maxOf { it.date }),
            confidence = when {
                shows.size >= HIGH_CONFIDENCE_SHOWS -> Confidence.HIGH
                shows.size >= MEDIUM_CONFIDENCE_SHOWS -> Confidence.MEDIUM
                else -> Confidence.LOW
            },
            songs = songs,
            warning = null,
        )
    }

    // Same song for every variant: without version suffixes, lowercase, curly apostrophe as straight
    fun songKey(name: String): String {
        var key = name.trim()
        while (true) {
            val suffix = SUFFIX.find(key) ?: break
            if (!VERSION_WORDS.containsMatchIn(suffix.groupValues[1])) break
            key = key.substring(0, suffix.range.first)
        }
        return key.ifBlank { name }.replace('’', '\'').replace('‘', '\'').lowercase().replace(SPACES, " ").trim()
    }

    private fun weightOf(sizeRatio: Double): Double? = when {
        sizeRatio < INCOMPLETE_RATIO -> null
        sizeRatio <= FESTIVAL_RATIO -> FESTIVAL_WEIGHT
        else -> 1.0
    }

    // Position counts the whole show (all sets, no tapes). A song played twice counts once, at the first position.
    // The relative position (0 opens, 1 closes) orders the prediction: a short festival set doesn't pull the closer
    // to the middle (Everlong was 9th in a festival and 24th in a full show, manual test of #29)
    private fun appearancesBySong(shows: List<Show>): Map<String, List<Appearance>> {
        val bySong = LinkedHashMap<String, MutableList<Appearance>>()
        for (show in shows) {
            val seen = mutableSetOf<String>()
            show.songs.forEachIndexed { index, song ->
                val key = songKey(song.name)
                if (seen.add(key)) {
                    val relative = if (show.songs.size == 1) 1.0 else index.toDouble() / (show.songs.size - 1)
                    bySong.getOrPut(key) { mutableListOf() } += Appearance(song.name, index + 1, relative, song.encore, song.coverOf, show.weight)
                }
            }
        }
        return bySong
    }

    private fun predictedSong(appearances: List<Appearance>, totalWeight: Double): Ranked {
        val weight = appearances.sumOf { it.weight }
        // Most frequent name first; on a tie, the one of the most recent show (the shows are sorted by date)
        val names = appearances.groupBy { it.name }.entries.sortedByDescending { (_, it) -> it.sumOf(Appearance::weight) }.map { it.key }
        return Ranked(
            PredictedSong(
                title = names.first(),
                percentage = (weight / totalWeight * 100).roundToInt(),
                typicalPosition = appearances.map { it.position.toDouble() }.median().roundToInt(),
                // Encore song: played in the encore in most of its (weighted) appearances
                encore = appearances.filter { it.encore }.sumOf { it.weight } > weight / 2,
                coverOf = appearances.firstNotNullOfOrNull { it.coverOf },
                variants = names.drop(1),
            ),
            appearances.map { it.relativePosition }.median(),
        )
    }

    private fun Setlist.playedSongs() = sets.flatMap { set ->
        set.songs.filter { !it.tape && it.name.isNotBlank() }.map { PlayedSong(it.name, set.encore, it.coverOf) }
    }

    private fun List<Double>.median(): Double = sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

    private data class PlayedSong(val name: String, val encore: Boolean, val coverOf: String?)

    private data class Show(val date: LocalDate, val songs: List<PlayedSong>, val weight: Double = 1.0)

    private data class Appearance(
        val name: String,
        val position: Int,
        val relativePosition: Double,
        val encore: Boolean,
        val coverOf: String?,
        val weight: Double,
    )

    private data class Ranked(val song: PredictedSong, val medianRelativePosition: Double)
}

data class Prediction(
    val basedOn: PredictionBasis?,
    val confidence: Confidence?,
    val songs: List<PredictedSong>,
    // Plan B of the spike: no recent setlist, the frontend shows the warning and offers a search instead
    val warning: String?,
) {
    companion object {
        const val NO_RECENT_SETLISTS = "no_recent_setlists"

        fun noRecentSetlists() = Prediction(basedOn = null, confidence = null, songs = emptyList(), warning = NO_RECENT_SETLISTS)
    }
}

data class PredictionBasis(val shows: Int, val weightedShows: Double, val from: LocalDate, val to: LocalDate)

enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
    ;

    @JsonValue
    fun json() = name.lowercase()
}

// percentage: weighted share of the shows (a festival set counts half). variants: other names seen for the song
data class PredictedSong(
    val title: String,
    val percentage: Int,
    val typicalPosition: Int,
    val encore: Boolean,
    val coverOf: String?,
    val variants: List<String>,
)
