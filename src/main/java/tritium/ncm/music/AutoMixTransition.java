package tritium.ncm.music;

import tritium.ncm.music.automix.AutoMixPlan;

/** Two decks driven by media positions, so pausing never advances a fade. */
final class AutoMixTransition {
    interface Deck {
        float getCurrentTimeMillis();
        boolean isFinished();
        boolean isPausing();
        void startAt(float millis);
        void setMixGain(double gain);
        void setMixFilter(double lowPassHz, double highPassHz);
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

    AutoMixTransition(Deck outgoing, Deck incoming, AutoMixPlan plan) {
        this.outgoing = outgoing;
        this.incoming = incoming;
        this.plan = plan;
    }

    void start(float volume) {
        incoming.setAnalysisEnabled(false);
        incoming.setVolume(volume);
        incoming.setMixGain(0);
        incoming.startAt((float) plan.startBMillis());
    }

    void tick(Runnable onSwitch) {
        if (complete || current().isPausing() && !current().isFinished()) return;
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

    boolean isComplete() { return complete; }
    boolean hasSwitched() { return switched; }
    Deck current() { return switched ? incoming : outgoing; }

    void pause() {
        if (!outgoing.isFinished()) outgoing.pause();
        incoming.pause();
    }

    void resume() {
        if (!outgoing.isFinished()) outgoing.unpause();
        incoming.unpause();
    }

    void setVolume(float volume) {
        outgoing.setVolume(volume);
        incoming.setVolume(volume);
    }

    /** Seek/manual selection keeps the song currently shown and disposes the other voice. */
    void cancel() {
        if (complete) return;
        (switched ? outgoing : incoming).close();
        restore(current());
        current().setAnalysisEnabled(true);
        complete = true;
    }

    private static void restore(Deck deck) {
        deck.setMixGain(1);
        deck.setMixFilter(0, 0);
    }
}
