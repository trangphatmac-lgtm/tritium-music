package tritium.desktop.media;

/** Commands are executed on the player thread, never on a native callback thread. */
record MediaCommand(Action action, long positionMillis, Long trackId, double volume) {
    enum Action { PLAY, PAUSE, PLAY_PAUSE, PREVIOUS, NEXT, SEEK, SEEK_RELATIVE, VOLUME }

    static MediaCommand button(Action action) { return new MediaCommand(action, 0, null, 0); }
    static MediaCommand seek(long millis) { return new MediaCommand(Action.SEEK, millis, null, 0); }
    static MediaCommand seek(long millis, long trackId) { return new MediaCommand(Action.SEEK, millis, trackId, 0); }
    static MediaCommand relativeSeek(long millis) { return new MediaCommand(Action.SEEK_RELATIVE, millis, null, 0); }
    static MediaCommand volume(double value) { return new MediaCommand(Action.VOLUME, 0, null, value); }
}
