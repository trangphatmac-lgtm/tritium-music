package tritium.ncm.music;

import tritium.ncm.music.automix.AutoMixPlan;
import tritium.ncm.music.automix.AutoMixAutomation;

import java.util.List;

/** Two decks driven by media positions, so pausing never advances a fade. */
final class AutoMixTransition {
    interface Deck {
        float getCurrentTimeMillis();
        boolean isFinished();
        boolean isPausing();
        void startAt(float millis);
        void setMixGain(double gain);
        void setMixFilter(double lowPassHz, double highPassHz);
        void setMixEqualizer(List<AutoMixAutomation.FilterSetting> settings);
        void setVolume(float volume);
        void setAnalysisEnabled(boolean enabled);
        void pause();
        void unpause();
        void close();
    }

    final Deck outgoing;
    final Deck incoming;
    final AutoMixPlan plan;
    private boolean switched;
    private boolean complete;
    private boolean incomingStarted;
    private boolean outgoingReleased;
    private double volume;
    private double lastGainA = Double.NaN, lastGainB = Double.NaN;
    private List<AutoMixAutomation.FilterSetting> lastFiltersA, lastFiltersB;

    AutoMixTransition(Deck outgoing, Deck incoming, AutoMixPlan plan) {
        this.outgoing = outgoing;
        this.incoming = incoming;
        this.plan = plan;
    }

    void start(float volume) {
        this.volume = volume;
        incoming.setAnalysisEnabled(false);
        incoming.setVolume(volume);
        incoming.setMixGain(0);
        if (!plan.fullMode() || outgoing.getCurrentTimeMillis() >= plan.incomingStartAMillis()) startIncoming();
        if (plan.fullMode()) applyAutomation(outgoing.getCurrentTimeMillis(), plan.startBMillis(), false);
    }

    private void startIncoming() {
        if (incomingStarted) return;
        // Install the entry filter while B is still muted, before its first audio block.
        if (plan.fullMode()) incoming.setMixEqualizer(plan.automation().b().filtersAt(plan.startBMillis()));
        incoming.setVolume((float) volume);
        incoming.startAt((float) plan.startBMillis());
        incomingStarted = true;
    }

    void tick(Runnable onSwitch) {
        if (complete || current().isPausing() && !current().isFinished()) return;
        if (plan.fullMode()) { tickIndependent(onSwitch); return; }
        double a = outgoing.isFinished() ? plan.endAMillis() : outgoing.getCurrentTimeMillis();
        double b = incoming.getCurrentTimeMillis();
        double outGain = plan.outgoingGain(a), inGain = plan.incomingGain(b);
        // Some asymmetric curves exceed unity in sum. Reserve headroom for correlated peaks.
        double headroom = Math.max(1, outGain + inGain);
        outgoing.setMixGain(outGain / headroom);
        incoming.setMixGain(inGain / headroom);
        outgoing.setMixFilter(plan.outgoingLowPassHz(a), plan.outgoingHighPassHz(a));
        incoming.setMixFilter(plan.incomingLowPassHz(b), plan.incomingHighPassHz(b));
        if (!switched && (a >= plan.switchAMillis() || a >= plan.endAMillis())) {
            // The caller publishes the new song/player together while holding its playback monitor.
            outgoing.setAnalysisEnabled(false);
            incoming.setAnalysisEnabled(true);
            onSwitch.run();
            switched = true;
        }
        if (a >= plan.endAMillis() || b >= plan.startBMillis() + plan.durationMillis()) {
            if (!switched) {
                outgoing.setAnalysisEnabled(false);
                incoming.setAnalysisEnabled(true);
                onSwitch.run();
                switched = true;
            }
            outgoing.close();
            restore(incoming);
            complete = true;
        }
    }

    private void tickIndependent(Runnable onSwitch) {
        double a = outgoingReleased || outgoing.isFinished() ? plan.endAMillis() : outgoing.getCurrentTimeMillis();
        if (!incomingStarted && a >= plan.incomingStartAMillis()) startIncoming();
        double b = incomingStarted ? incoming.getCurrentTimeMillis() : plan.startBMillis();
        boolean aEnded = outgoingReleased || outgoing.isFinished() || a >= plan.endAMillis();
        applyAutomation(a, b, aEnded);
        if (!switched && incomingStarted && (a >= plan.switchAMillis() || aEnded)) {
            outgoing.setAnalysisEnabled(false);
            incoming.setAnalysisEnabled(true);
            onSwitch.run();
            switched = true;
        }
        if (aEnded && !outgoingReleased) { outgoing.close(); outgoingReleased = true; }
        // B may finish its fade before or after A. Neither clock truncates the other envelope.
        if (outgoingReleased && incomingStarted && b >= plan.endBMillis()) {
            restore(incoming);
            complete = true;
        }
    }

    private void applyAutomation(double a, double b, boolean aEnded) {
        double out = aEnded ? 0 : plan.outgoingGain(a);
        double in = incomingStarted ? plan.incomingGain(b) : 0;
        double headroom = Math.max(1, out + in);
        double gainA = out / headroom, gainB = in / headroom;
        if (!outgoingReleased && gainA != lastGainA) outgoing.setMixGain(gainA);
        if (gainB != lastGainB) incoming.setMixGain(gainB);
        lastGainA = gainA; lastGainB = gainB;
        if (!aEnded) {
            var filters = plan.automation().a().filtersAt(a);
            if (!filters.equals(lastFiltersA)) outgoing.setMixEqualizer(filters);
            lastFiltersA = filters;
        }
        if (incomingStarted) {
            var filters = plan.automation().b().filtersAt(b);
            if (!filters.equals(lastFiltersB)) incoming.setMixEqualizer(filters);
            lastFiltersB = filters;
        }
    }

    boolean hasIncomingStarted() { return incomingStarted; }
    boolean hasOutgoingReleased() { return outgoingReleased; }

    boolean isComplete() { return complete; }
    boolean hasSwitched() { return switched; }
    Deck current() { return switched ? incoming : outgoing; }

    void pause() {
        if (!outgoing.isFinished()) outgoing.pause();
        incoming.pause();
    }

    void resume() {
        lastFiltersA = lastFiltersB = null;
        if (plan.fullMode()) {
            boolean ended = outgoingReleased || outgoing.isFinished();
            // pause() removes effects. Reinstall before audio resumes, not one scheduler tick later.
            applyAutomation(ended ? plan.endAMillis() : outgoing.getCurrentTimeMillis(),
                    incomingStarted ? incoming.getCurrentTimeMillis() : plan.startBMillis(), ended);
        }
        if (!outgoing.isFinished()) outgoing.unpause();
        // A delayed B must stay silent until its scheduled media-clock start.
        if (incomingStarted) incoming.unpause();
    }

    void setVolume(float volume) {
        this.volume = volume;
        outgoing.setVolume(volume);
        incoming.setVolume(volume);
    }

    /** Seek/manual selection keeps the song currently shown and disposes the other voice. */
    void cancel() {
        if (complete) return;
        if (!switched) incoming.close();
        else if (!outgoingReleased) { outgoing.close(); outgoingReleased = true; }
        restore(current());
        current().setAnalysisEnabled(true);
        complete = true;
    }

    private static void restore(Deck deck) {
        deck.setMixGain(1);
        deck.setMixFilter(0, 0);
        deck.setMixEqualizer(List.of());
    }
}
