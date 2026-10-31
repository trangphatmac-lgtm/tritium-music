package tritium.ncm.music.effects;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Keeps each playback deck bound to its own song while requests complete asynchronously. */
public final class AudioEffectController {
    private final AudioEffectService service;
    private final Executor executor;
    private final Map<AudioEffectTarget, Binding> bindings = new WeakHashMap<>();

    private static final class Binding {
        final WeakReference<AudioEffectTarget> target;
        final long songId;
        long revision;
        AudioEffectService.Result result;
        AudioEffectService.Selection selection;
        long updatedAtMillis;
        Binding(AudioEffectTarget target, long songId) {
            this.target = new WeakReference<>(target); this.songId = songId;
        }
    }

    public AudioEffectController() {
        this(new AudioEffectService(), new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), runnable -> {
                    Thread thread = new Thread(runnable, "Audio effect lookup");
                    thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy()));
    }
    AudioEffectController(AudioEffectService service, Executor executor) {
        this.service = service; this.executor = executor;
    }

    public synchronized void register(AudioEffectTarget target, long songId,
                                      AudioEffectService.Selection selection, String cookie) {
        if (target == null || target.isAudioEffectClosed()) return;
        removeClosed();
        Binding binding = new Binding(target, songId);
        bindings.put(target, binding);
        configure(binding, selection, cookie);
    }

    /** Includes an AutoMix incoming deck, so settings cannot leave it with an old configuration. */
    public synchronized void refresh(AudioEffectService.Selection selection, String cookie) {
        removeClosed();
        for (Binding binding : bindings.values()) configure(binding, selection, cookie);
    }

    public synchronized AudioEffectService.Result status(AudioEffectTarget target) {
        Binding binding = bindings.get(target);
        return binding == null ? null : binding.result;
    }

    /** No lookup or configuration changes: safe to poll only while the overlay is visible. */
    public synchronized AudioEffectDebugSnapshot debugSnapshot(AudioEffectTarget current,
                                                               AudioEffectService.Selection selection) {
        List<AudioEffectDebugSnapshot.Deck> decks = new ArrayList<>();
        for (Binding binding : bindings.values()) {
            AudioEffectTarget target = binding.target.get();
            if (target == null || target.isAudioEffectClosed()) continue;
            decks.add(new AudioEffectDebugSnapshot.Deck(binding.songId, target == current, binding.revision,
                    binding.updatedAtMillis, binding.selection, binding.result, target.audioEffectDebugSnapshot()));
        }
        decks.sort(Comparator.comparing(AudioEffectDebugSnapshot.Deck::current).reversed()
                .thenComparingLong(AudioEffectDebugSnapshot.Deck::songId));
        return new AudioEffectDebugSnapshot(selection, decks);
    }

    private void removeClosed() {
        bindings.entrySet().removeIf(entry -> entry.getKey() == null || entry.getKey().isAudioEffectClosed());
    }

    private void configure(Binding binding, AudioEffectService.Selection selection, String cookie) {
        long revision = ++binding.revision;
        binding.selection = selection;
        var fallback = service.fallback(selection);
        apply(binding, fallback);
        if (binding.songId <= 0 || !selection.needsRecommendation()) return;
        binding.result = new AudioEffectService.Result(fallback.configuration(), "loading", fallback.tone(), fallback.usedDiy(), fallback.diagnostics());
        try {
            executor.execute(() -> {
                synchronized (this) { if (!current(binding, revision)) return; }
                AudioEffectService.Result result;
                try { result = service.load(binding.songId, selection, cookie); }
                catch (RuntimeException ignored) { result = fallback; }
                synchronized (this) {
                    // Both validation and application share the settings lock: turning Off always wins.
                    if (current(binding, revision)) apply(binding, result);
                }
            });
        } catch (RejectedExecutionException ignored) { binding.result = fallback; }
    }

    private boolean current(Binding binding, long revision) {
        AudioEffectTarget target = binding.target.get();
        return binding.revision == revision && target != null && !target.isAudioEffectClosed()
                && bindings.get(target) == binding;
    }

    private void apply(Binding binding, AudioEffectService.Result result) {
        AudioEffectTarget target = binding.target.get();
        if (target == null || target.isAudioEffectClosed()) return;
        target.setAudioEffect(result.configuration());
        binding.result = result;
        binding.updatedAtMillis = System.currentTimeMillis();
    }
}
