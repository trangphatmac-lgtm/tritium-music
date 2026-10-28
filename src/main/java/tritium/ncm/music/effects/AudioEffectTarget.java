package tritium.ncm.music.effects;

import com.google.gson.JsonObject;

/** A playback deck that can receive a persistent effect independently of its mix filters. */
public interface AudioEffectTarget {
    void setAudioEffect(JsonObject configuration);
    boolean isAudioEffectClosed();
}
