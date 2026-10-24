package tritium.screens.ncm.lyrics;

/** All scenes draw in a 720-unit-high viewport using the existing OpenGL renderer. */
interface LyricScene extends AutoCloseable {
    void render(LyricCanvas canvas, LyricFrame frame);
    default boolean ownSubtitles() { return false; }
    default void reset() { }
    @Override default void close() { }
}
