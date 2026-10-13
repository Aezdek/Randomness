package xyz.aezdek.aria.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataTest {
    private fun check(title: String, channel: String, song: String, artist: String) =
        assertEquals(song to artist, Metadata.guess(title, channel))

    @Test fun officialVideo() = check("Daft Punk - One More Time (Official Video)", "Daft Punk", "One More Time", "Daft Punk")
    @Test fun vevoChannel() = check("The Weeknd - Blinding Lights (Official Audio)", "TheWeekndVEVO", "Blinding Lights", "The Weeknd")
    @Test fun topicChannel() = check("Blinding Lights", "The Weeknd - Topic", "Blinding Lights", "The Weeknd")
    @Test fun keepsRemasterOnTopic() = check("Bohemian Rhapsody (Remastered 2011)", "Queen - Topic", "Bohemian Rhapsody (Remastered 2011)", "Queen")
    @Test fun quotedTitle() = check("Tame Impala \"The Less I Know The Better\" (Official Video)", "Tame Impala", "The Less I Know The Better", "Tame Impala")
    @Test fun enDash() = check("Billie Eilish – BIRDS OF A FEATHER (Official Lyric Video)", "Billie Eilish", "BIRDS OF A FEATHER", "Billie Eilish")
    @Test fun pipeSuffix() = check("Coldplay - Yellow (Official Video) | Parachutes", "Coldplay", "Yellow", "Coldplay")
    @Test fun noArtistInTitle() = check("Lofi beats to study to | chill mix 2024", "Lofi Girl", "Lofi beats to study to", "Lofi Girl")
    @Test fun twoArtists() = check("ROSÉ & Bruno Mars - APT. (Official Music Video)", "ROSÉ", "APT.", "ROSÉ & Bruno Mars")

    @Test fun trackRoundTrip() {
        val t = Track("FGBhQbmPwH8", "One More Time", "Daft Punk", "Discovery", 322.0, "https://x/600x600bb.jpg", listOf(0.0 to 12.5))
        assertEquals(t, Track.fromJson(t.toJson()))
    }

    @Test fun rejectsBadIds() = assertEquals(null, Track.fromJson(org.json.JSONObject().put("id", "nope")))

    @Test fun formatsTime() {
        assertEquals("3:05", formatTime(185.0))
        assertEquals("1:01:01", formatTime(3661.0))
    }
}
