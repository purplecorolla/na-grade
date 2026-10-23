package app.nagrade.api.music

import java.text.Normalizer

// The same song under its different names: "Fooley (LEECH Ambient Queen Remix)", "Everlong - Live at Wembley",
// "don’t" and "don't". Used by the prediction (#29) to group setlist.fm names and by the playlist (#30) to compare
// them with Spotify titles: one rule for both, decided in the planning chat
object SongTitle {

    // A suffix only marks a version when it has one of these words. Any other suffix names another song:
    // "Epic (Part 2)", "Monkey Wrench - Part One"
    private val VERSION_WORDS = Regex("""\b(remix|mix|edit|version|live|acoustic|demo|remaster|remastered)\b""", RegexOption.IGNORE_CASE)
    private val PARENTHESIZED_SUFFIX = Regex("""\s*[(\[]([^()\[\]]*)[)\]]\s*$""")
    private val DASH_SUFFIX = Regex("""\s+[-–]\s+([^-–]*)$""")
    private val ACCENTS = Regex("""\p{M}+""")
    private val SPACES = Regex("""\s+""")

    fun normalize(title: String): String {
        var base = title.trim()
        while (true) {
            val suffix = PARENTHESIZED_SUFFIX.find(base) ?: DASH_SUFFIX.find(base) ?: break
            if (!VERSION_WORDS.containsMatchIn(suffix.groupValues[1])) break
            base = base.substring(0, suffix.range.first)
        }
        return strict(base.ifBlank { title })
    }

    // Case, accents and apostrophes only, keeping the version suffixes: "Marigold" and "Marigold - Remastered"
    // differ. For matches where the rest is weaker (the original version of a cover, found by the artist name)
    fun strict(title: String): String =
        Normalizer.normalize(title.trim(), Normalizer.Form.NFD)
            .replace(ACCENTS, "")
            .replace('’', '\'')
            .replace('‘', '\'')
            .lowercase()
            .replace(SPACES, " ")
            .trim()
}
