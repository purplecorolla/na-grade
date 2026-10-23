package app.nagrade.api.music

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

// One normalizer for the whole app: the prediction (#29) groups the setlist.fm names with it and the playlist (#30)
// compares them with the Spotify titles. Two rules would group one way and search another
class SongTitleTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        Fooley (LEECH Ambient Queen Remix)         | fooley
        FOOLEY (Live at Home)                      | fooley
        Everlong - Live at Wembley Stadium, 2008   | everlong
        Times Like These - Remastered 2011         | times like these
        Big Me - 2011 Remaster                     | big me
        Learn to Fly - Acoustic Version            | learn to fly
        Walk [Remastered]                          | walk
        Rope (Live) - Remastered                   | rope
        Send Me Out (Radio Edit)                   | send me out
        don’t piss me off                          | don't piss me off
        Canção do Mar                              | cancao do mar""",
    )
    fun `versions, case, apostrophes and accents are the same song`(title: String, normalized: String) {
        assertThat(SongTitle.normalize(title)).isEqualTo(normalized)
    }

    @ParameterizedTest(name = "{0} stays {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        Epic (Part 2)                | epic (part 2)
        Monkey Wrench - Part One     | monkey wrench - part one
        Song (Mixtape)               | song (mixtape)
        (Live)                       | (live)""",
    )
    fun `other parentheses and dashes name another song`(title: String, normalized: String) {
        assertThat(SongTitle.normalize(title)).isEqualTo(normalized)
    }
}
