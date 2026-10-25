package tritium.desktop.minecraft;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import tritium.minecraft.protocol.PlaybackState;
import tritium.minecraft.protocol.Wire;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.screens.ncm.LyricLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftLyricFeedTest {
    private final Gson json = new Gson();

    @Test void marksOnlySyntheticMessagesAndRetainsExistingHudTextOnTheWire() throws Exception {
        Music previousMusic = CloudMusic.currentlyPlaying;
        List<LyricLine> previousLyrics = new ArrayList<>(CloudMusic.lyrics);
        try {
            LyricLine placeholder = LyricLine.placeholder();
            LyricLine actual = new LyricLine(2500, placeholder.getLyric());
            LyricLine gap = new LyricLine(4000, "● ● ●"); gap.isBreakLine = true;
            CloudMusic.currentlyPlaying = json.fromJson("{\"id\":77,\"name\":\"Fixture\",\"ar\":[]}", Music.class);
            synchronized (CloudMusic.lyrics) {
                CloudMusic.lyrics.clear(); CloudMusic.lyrics.addAll(List.of(placeholder, actual, gap));
            }
            PlaybackState.Track captured = MinecraftConnectionManager.captureTrack();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Wire writer = new Wire(new ByteArrayInputStream(new byte[0]), bytes);
            writer.send(Wire.TRACK, json.toJson(captured));
            Wire reader = new Wire(new ByteArrayInputStream(bytes.toByteArray()), new ByteArrayOutputStream());
            PlaybackState.Track decoded = json.fromJson(reader.read().text(), PlaybackState.Track.class);
            assertEquals(3, decoded.lyrics.size());
            assertTrue(placeholder.isPlaceholder()); assertFalse(actual.isPlaceholder());
            assertTrue(decoded.lyrics.get(0).placeholder);
            assertEquals(placeholder.getLyric(), decoded.lyrics.get(0).text, "Existing 2D HUD keeps its localized empty state");
            assertFalse(decoded.lyrics.get(1).placeholder, "A real lyric with identical words remains a sung line");
            assertEquals(actual.getLyric(), decoded.lyrics.get(1).text);
            assertTrue(decoded.lyrics.get(2).breakLine); assertFalse(decoded.lyrics.get(2).placeholder);
            assertFalse(json.fromJson("{\"timestamp\":1000,\"text\":\"old sender\"}", PlaybackState.Line.class).placeholder);
        } finally {
            CloudMusic.currentlyPlaying = previousMusic;
            synchronized (CloudMusic.lyrics) { CloudMusic.lyrics.clear(); CloudMusic.lyrics.addAll(previousLyrics); }
        }
    }

    @Test void metadataComparisonDetectsSameTrackWordTimingChangesAndOwnsItsSnapshot() {
        Music previousMusic = CloudMusic.currentlyPlaying;
        List<LyricLine> previousLyrics = new ArrayList<>(CloudMusic.lyrics);
        try {
            CloudMusic.currentlyPlaying = json.fromJson("{\"id\":77,\"name\":\"Fixture\",\"ar\":[]}", Music.class);
            LyricLine line = new LyricLine(1000, "你好"); line.duration = 2000;
            line.translationText = "Hello"; line.romanizationText = "ni hao";
            line.words.add(new LyricLine.Word("你", 1000, 400));
            line.words.add(new LyricLine.Word("好", 1400, 500));
            synchronized (CloudMusic.lyrics) { CloudMusic.lyrics.clear(); CloudMusic.lyrics.add(line); }
            PlaybackState.Track first = MinecraftConnectionManager.captureTrack();
            String before = json.toJson(first);
            line.words.set(1, new LyricLine.Word("好", 1600, 700));
            PlaybackState.Track second = MinecraftConnectionManager.captureTrack();
            assertEquals(first.id, second.id); assertEquals(first.timeline, second.timeline);
            assertNotEquals(before, json.toJson(second), "Publisher compares full JSON, not just track ID or line count");
            assertEquals(before, json.toJson(first), "Later lyric edits cannot mutate an already captured frame");
            assertEquals(1600, second.lyrics.get(0).words.get(1).timestamp);
            assertEquals(700, second.lyrics.get(0).words.get(1).duration);
            assertEquals(1000, second.lyrics.get(0).timestamp); assertEquals(2000, second.lyrics.get(0).duration);
            assertEquals("Hello", second.lyrics.get(0).translation); assertEquals("ni hao", second.lyrics.get(0).romanization);
        } finally {
            CloudMusic.currentlyPlaying = previousMusic;
            synchronized (CloudMusic.lyrics) { CloudMusic.lyrics.clear(); CloudMusic.lyrics.addAll(previousLyrics); }
        }
    }
}
