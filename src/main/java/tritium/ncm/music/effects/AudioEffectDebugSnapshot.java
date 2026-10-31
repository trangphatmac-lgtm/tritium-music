package tritium.ncm.music.effects;

import java.util.List;

/** Read-only diagnostics for the selected mode and each still-open playback deck. */
public record AudioEffectDebugSnapshot(AudioEffectService.Selection selection, List<Deck> decks) {
    public AudioEffectDebugSnapshot { decks = List.copyOf(decks); }

    public record Deck(long songId, boolean current, long revision, long updatedAtMillis,
                       AudioEffectService.Selection selection, AudioEffectService.Result result,
                       AudioEffectDsp.DebugSnapshot dsp) { }
}
