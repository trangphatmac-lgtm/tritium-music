package tritium.ncm.music.lyrics;

import tritium.ncm.music.dto.Music;

/** Provider-neutral identity used only for matching lyrics, never for audio playback. */
public record LyricSong(String id, String title, String artist, String album, long durationMs, String providerKey, String catalogId) {
    public LyricSong {
        title = title == null ? "" : title;
        artist = artist == null ? "" : artist;
        album = album == null || album.equalsIgnoreCase("Unknown Album") ? "" : album;
    }

    public static LyricSong fromMusic(Music music) {
        return new LyricSong(String.valueOf(music.getId()), music.getName(),
                music.getArtists() == null ? "" : music.getArtistsName(),
                music.getAlbum() == null ? "" : music.getAlbum().getName(), music.getDuration(), "", "");
    }
}
