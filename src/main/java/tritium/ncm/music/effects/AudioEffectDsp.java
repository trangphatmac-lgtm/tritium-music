package tritium.ncm.music.effects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import repackage.com.jsyn.ports.UnitInputPort;
import repackage.com.jsyn.ports.UnitOutputPort;
import repackage.com.jsyn.unitgen.UnitGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Portable stereo implementation of the recovered Smart/Master parameter protocol.
 * This is NOT the proprietary native binary or a bit-exact emulation. Ten-band EQ uses
 * octave-width RBJ biquads; PEQ 6/7/8 are interpreted as peak/low-shelf/high-shelf (the
 * recovered numerical enums were not conclusively named). Other PEQ types are bypassed.
 * Reverb uses a damped Schroeder network and tapped early reflections; stereo enhancement
 * uses mid/side width, delayed decorrelation, and optional side-spectrum shelving. Their scales, tone insert routing,
 * and the order EQ -> BT -> PEQ -> compressor -> stereo -> reverb -> limiter are desktop
 * adaptations where the native implementation is unavailable. Delay values are milliseconds,
 * compressor times seconds, level/EQ values dB. Output protection only affects active effects.
 *
 * JSON, coefficient calculation and all delay allocation happen in setConfiguration/reset.
 * Plans are immutable; filter/envelope/delay state belongs exclusively to the render thread.
 * No locks, network, JSON traversal or per-sample allocation occur in process/generate.
 */
public final class AudioEffectDsp extends UnitGenerator {
    public final UnitInputPort input = new UnitInputPort(2, "Input");
    public final UnitOutputPort output = new UnitOutputPort(2, "Output");
    private static final double[] EQ_HZ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    private final double sampleRate;
    private final int fadeFrames;
    private final AtomicReference<Change> pending = new AtomicReference<>();
    private Plan plan;
    private Processor current, previous;
    private int fadeRemaining;
    private record Change(Processor processor, boolean fade) {}

    public AudioEffectDsp(double sampleRate) {
        if (!Double.isFinite(sampleRate) || sampleRate < 8000 || sampleRate > 384000)
            throw new IllegalArgumentException("Unsupported DSP sample rate");
        this.sampleRate = sampleRate;
        fadeFrames = Math.max(1, (int) (sampleRate * .02));
        addPort(input); addPort(output);
        plan = compile(null, sampleRate);
        current = new Processor(plan, sampleRate);
    }

    public synchronized void setConfiguration(JsonObject config) {
        plan = compile(config, sampleRate);
        pending.set(new Change(new Processor(plan, sampleRate), true));
    }

    /** Clear filter/reverb history after a discontinuity while retaining the selected plan. */
    public synchronized void reset() { pending.set(new Change(new Processor(plan, sampleRate), false)); }

    @Override public void generate(int start, int limit) {
        process(input.getValues(0), input.getValues(1), output.getValues(0), output.getValues(1), start, limit);
    }

    /** Render stereo PCM; also usable for deterministic offline tests. Output may alias input. */
    public void process(double[] left, double[] right, double[] outLeft, double[] outRight, int start, int limit) {
        Change change = pending.getAndSet(null);
        if (change != null) {
            previous = change.fade ? current : null;
            current = change.processor;
            fadeRemaining = change.fade ? fadeFrames : 0;
        }
        for (int i = start; i < limit; i++) {
            double l = finite(left[i], 0), r = finite(right[i], 0);
            current.tick(l, r);
            double a = current.left, b = current.right;
            if (fadeRemaining > 0) {
                previous.tick(l, r);
                double wet = 1 - (double) fadeRemaining / fadeFrames;
                a = previous.left + wet * (a - previous.left);
                b = previous.right + wet * (b - previous.right);
                if (--fadeRemaining == 0) previous = null;
            }
            outLeft[i] = a; outRight[i] = b;
        }
    }

    private record Coeff(double b0, double b1, double b2, double a1, double a2) {}
    private record Compressor(double pre, double post, double threshold, double ratio,
                              double attack, double release, double range) {}
    private record Stereo(double width, double decorrelation, double ambience, boolean shape) {}
    private record Limiter(double threshold, double attack, double release, double outputGain,
                           double ratio, boolean infinite) {}
    private record Reverb(boolean late, boolean early, double predelay, double decay, double damping,
                          double size, double density, double diffusion, double quality, double width,
                          double earlyDelay, double earlySize, int pattern, double dry, double er,
                          double wet, Coeff[] tone) {}
    private record Plan(Coeff[] filters, double gain, Compressor compressor, Stereo stereo,
                        Reverb reverb, Limiter limiter, boolean active) {}

    private static Plan compile(JsonObject config, double rate) {
        JsonObject c = config == null ? new JsonObject() : config;
        if (c.has("effect") && c.get("effect").isJsonObject()) c = c.getAsJsonObject("effect");
        if (c.has("aev2") && c.get("aev2").isJsonObject()) c = c.getAsJsonObject("aev2");
        List<Coeff> filters = new ArrayList<>();
        JsonObject eq = object(c, "eq"), bt = object(c, "bt"), peq = object(c, "peq");
        if (on(eq)) {
            JsonArray gains = array(eq, "eqs");
            for (int i = 0; i < Math.min(10, gains.size()); i++) {
                double gain = number(gains.get(i), 0, -24, 24);
                if (Math.abs(gain) > 1e-8) filters.add(biquad(6, EQ_HZ[i], 1.41421356237, gain, rate));
            }
        }
        if (on(bt)) {
            double bass = value(bt, "bass", 0, -24, 24), treble = value(bt, "treble", 0, -24, 24);
            if (bass != 0) filters.add(biquad(7, positive(bt, "bassFreq", 120, 20, rate * .45),
                    positive(bt, "bassQ", .7071, .1, 10), bass, rate));
            if (treble != 0) filters.add(biquad(8, positive(bt, "trebleFreq", 6000, 20, rate * .45),
                    positive(bt, "trebleQ", .7071, .1, 10), treble, rate));
        }
        double gain = 1;
        if (on(peq)) {
            gain = db(value(peq, "gain", value(peq, "overallgain", 0, -24, 24), -24, 24));
            // At most nine slots; last definition for a repeated band wins, as in the native setter.
            Coeff[] slots = new Coeff[9];
            JsonArray bands = array(peq, "f");
            for (int i = 0; i < Math.min(64, bands.size()); i++) {
                if (!bands.get(i).isJsonObject()) continue;
                JsonObject band = bands.get(i).getAsJsonObject();
                int index = (int) value(band, "band", i, -1, 9);
                if (index < 0 || index > 8) continue;
                int type = (int) value(band, "type", -1, -1, 99);
                slots[index] = on(band) && type >= 6 && type <= 8
                        ? biquad(type, value(band, "freq", 1000, 10, rate * .45),
                        positive(band, "q", .7071, .1, 20), value(band, "gain", 0, -24, 24), rate) : null;
            }
            for (Coeff slot : slots) if (slot != null) filters.add(slot);
        }
        Compressor compressor = null;
        JsonObject cmp = object(c, "cmp");
        if (on(cmp)) compressor = new Compressor(db(value(cmp, "pregain", 0, -36, 24)),
                db(value(cmp, "postgain", 0, -36, 24)), value(cmp, "threshold", 0, -80, 0),
                value(cmp, "ratio", 1, 1, 40), Math.exp(-1 / (rate * positive(cmp, "attack", .001, .0001, 2))),
                Math.exp(-1 / (rate * positive(cmp, "release", .1, .005, 5))),
                Math.abs(value(cmp, "range", 0, -60, 60)));
        Stereo stereo = null;
        JsonObject se = object(c, "se");
        if (on(se)) stereo = new Stereo(1 + value(se, "presence", 0, 0, 10) * .08,
                value(se, "stereoizer", 0, 0, 10) * .025,
                value(se, "ambience", 0, 0, 10) * .025, bool(se, "sshaper", false));
        Reverb reverb = null;
        JsonObject rvb = object(c, "rvb");
        if (on(rvb)) {
            JsonObject late = object(rvb, "rvb"), er = object(rvb, "er"), levels = object(rvb, "ol");
            List<Coeff> tone = new ArrayList<>();
            JsonObject tc = object(rvb, "tc");
            if (on(tc)) {
                JsonArray f = array(tc, "f");
                for (int i = 0; i < Math.min(9, f.size()); i++) {
                    if (!f.get(i).isJsonObject()) continue;
                    JsonObject t = f.get(i).getAsJsonObject();
                    // Desktop wet-bus interpretation of the recovered curve/insert protocol.
                    int curve = (int) value(t, "curve", 1, 0, 2);
                    tone.add(biquad(curve == 0 ? 7 : curve == 2 ? 8 : 6,
                            value(t, "freq", 1000, 10, rate * .45), positive(t, "q", .7071, .1, 10),
                            value(t, "gain", 0, -24, 24), rate));
                }
            }
            reverb = new Reverb(bool(late, "on", !late.entrySet().isEmpty()), on(er),
                    value(late, "pdelay", 20, 0, 500), positive(late, "dtime", 1, .1, 10),
                    positive(late, "hfdamping", 6, .1, 20), value(late, "rshape", 50, 0, 100),
                    value(late, "density", 60, 0, 100), value(late, "diffusion", 60, 0, 100),
                    value(late, "q", 6, 1, 10), value(late, "swidth", 100, 0, 200) / 100,
                    value(er, "sdelay", 20, 0, 500), value(er, "rsize", 50, 0, 100),
                    (int) value(er, "pattern", 0, 0, 20), db(value(levels, "dry", 0, -96, 6)),
                    db(value(levels, "er", -24, -96, 6)), db(value(levels, "rvb", -24, -96, 6)),
                    tone.toArray(Coeff[]::new));
        }
        JsonObject limiter = object(c, "limiter");
        Limiter limit = on(limiter) ? new Limiter(value(limiter, "threshold", 0, -36, 0),
                Math.exp(-1 / (rate * positive(limiter, "attack", .002, .0001, .5))),
                Math.exp(-1 / (rate * positive(limiter, "release", .04, .005, 5))),
                db(value(limiter, "outputgain", 0, -36, 12)), value(limiter, "ratio", 4, 1, 40),
                bool(limiter, "infiniteRatio", true)) : null;
        boolean active = !filters.isEmpty() || gain != 1 || compressor != null || stereo != null || reverb != null || on(limiter);
        return new Plan(filters.toArray(Coeff[]::new), gain, compressor, stereo, reverb, limit, active);
    }

    /** RBJ normalized biquads, with independent transposed-direct-form state per channel. */
    private static Coeff biquad(int type, double hz, double q, double gain, double rate) {
        double w = 2 * Math.PI * Math.max(10, Math.min(rate * .45, hz)) / rate;
        double cos = Math.cos(w), sin = Math.sin(w), a = Math.pow(10, gain / 40), alpha = sin / (2 * q);
        double b0, b1, b2, a0, a1, a2;
        if (type == 7 || type == 8) {
            double beta = 2 * Math.sqrt(a) * alpha;
            if (type == 7) {
                b0 = a * ((a + 1) - (a - 1) * cos + beta);
                b1 = 2 * a * ((a - 1) - (a + 1) * cos);
                b2 = a * ((a + 1) - (a - 1) * cos - beta);
                a0 = (a + 1) + (a - 1) * cos + beta;
                a1 = -2 * ((a - 1) + (a + 1) * cos);
                a2 = (a + 1) + (a - 1) * cos - beta;
            } else {
                b0 = a * ((a + 1) + (a - 1) * cos + beta);
                b1 = -2 * a * ((a - 1) + (a + 1) * cos);
                b2 = a * ((a + 1) + (a - 1) * cos - beta);
                a0 = (a + 1) - (a - 1) * cos + beta;
                a1 = 2 * ((a - 1) - (a + 1) * cos);
                a2 = (a + 1) - (a - 1) * cos - beta;
            }
        } else {
            b0 = 1 + alpha * a; b1 = -2 * cos; b2 = 1 - alpha * a;
            a0 = 1 + alpha / a; a1 = -2 * cos; a2 = 1 - alpha / a;
        }
        return new Coeff(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    private static final class Filter {
        final Coeff c;
        double z1, z2;
        Filter(Coeff c) { this.c = c; }
        double tick(double x) {
            double y = c.b0 * x + z1;
            z1 = c.b1 * x - c.a1 * y + z2;
            z2 = c.b2 * x - c.a2 * y;
            if (!Double.isFinite(y) || Math.abs(y) > 1e6) { z1 = z2 = 0; return 0; }
            if (Math.abs(z1) < 1e-30) z1 = 0;
            if (Math.abs(z2) < 1e-30) z2 = 0;
            return y;
        }
    }

    private static final class Processor {
        final Plan p;
        final Filter[] l, r;
        final Room room;
        final Delay decorrelation;
        final Filter[] spectrumShape;
        double envelope, limitEnvelope, left, right;
        Processor(Plan p, double rate) {
            this.p = p; l = filters(p.filters); r = filters(p.filters);
            room = p.reverb == null ? null : new Room(p.reverb, rate);
            decorrelation = p.stereo == null ? null : new Delay((int) (rate * .013));
            // The original spectrumShaper coefficients are unknown. Keep low frequencies
            // focused and add gentle high-frequency spread instead of introducing distortion.
            spectrumShape = p.stereo != null && p.stereo.shape
                    ? filters(new Coeff[]{biquad(7, 180, .7071, -3, rate), biquad(8, 4500, .7071, 1.5, rate)})
                    : new Filter[0];
        }
        void tick(double inL, double inR) {
            if (!p.active) { left = inL; right = inR; return; }
            double a = inL * p.gain, b = inR * p.gain;
            for (int i = 0; i < l.length; i++) { a = l[i].tick(a); b = r[i].tick(b); }
            if (p.compressor != null) {
                Compressor c = p.compressor;
                a *= c.pre; b *= c.pre;
                double peak = Math.max(Math.abs(a), Math.abs(b));
                double coefficient = peak > envelope ? c.attack : c.release;
                envelope = coefficient * envelope + (1 - coefficient) * peak;
                double over = 20 * Math.log10(Math.max(1e-12, envelope)) - c.threshold;
                double reduction = Math.max(0, over) * (1 - 1 / c.ratio);
                if (c.range > 0) reduction = Math.min(reduction, c.range);
                double gain = db(-reduction) * c.post;
                a *= gain; b *= gain;
            }
            if (p.stereo != null) {
                Stereo s = p.stereo;
                double mid = (a + b) * .5, side = (a - b) * .5;
                double delayed = decorrelation.push(mid);
                double spread = side * s.width + (mid - delayed) * s.decorrelation + delayed * s.ambience;
                for (Filter filter : spectrumShape) spread = filter.tick(spread);
                a = mid + spread; b = mid - spread;
            }
            if (room != null) { room.tick(a, b); a = room.left; b = room.right; }
            double ceiling = 1;
            if (p.limiter != null) {
                Limiter limiter = p.limiter;
                double peak = Math.max(Math.abs(a), Math.abs(b));
                double coefficient = peak > limitEnvelope ? limiter.attack : limiter.release;
                limitEnvelope = coefficient * limitEnvelope + (1 - coefficient) * peak;
                double over = Math.max(0, 20 * Math.log10(Math.max(1e-12, limitEnvelope)) - limiter.threshold);
                double gain = db(-over * (limiter.infinite ? 1 : 1 - 1 / limiter.ratio)) * limiter.outputGain;
                a *= gain; b *= gain;
                if (limiter.infinite) ceiling = Math.min(1, db(limiter.threshold) * limiter.outputGain);
            }
            left = protect(a, ceiling); right = protect(b, ceiling);
        }
    }

    private static final class Delay {
        final double[] values;
        int position;
        Delay(int frames) { values = new double[Math.max(1, frames)]; }
        double push(double value) {
            double out = values[position]; values[position] = value;
            if (++position == values.length) position = 0;
            return out;
        }
        double tap(int frames) {
            int index = position - Math.min(values.length, Math.max(1, frames));
            if (index < 0) index += values.length;
            return values[index];
        }
    }
    private static final class Comb {
        final Delay delay;
        final double feedback, damping;
        double low;
        Comb(int frames, double rate, double decay, double damping) {
            delay = new Delay(frames); feedback = Math.pow(10, -3 * frames / (rate * decay));
            this.damping = damping;
        }
        double tick(double input) {
            double out = delay.values[delay.position];
            low += damping * (out - low);
            delay.push(input + low * feedback);
            return out;
        }
    }
    private static final class Allpass {
        final Delay delay;
        final double feedback;
        Allpass(int size, double feedback) { delay = new Delay(size); this.feedback = feedback; }
        double tick(double x) {
            double old = delay.values[delay.position];
            double y = old - feedback * x;
            delay.push(x + feedback * y);
            return y;
        }
    }
    private static final class Room {
        final Reverb p;
        final Delay preL, preR, earlyL, earlyR;
        final Comb[] combL, combR;
        final Allpass[] apL, apR;
        final Filter[] toneL, toneR;
        final int[] tapsL = new int[6], tapsR = new int[6];
        double left, right;
        Room(Reverb p, double rate) {
            this.p = p;
            preL = new Delay((int) (rate * p.predelay / 1000)); preR = new Delay(preL.values.length);
            earlyL = new Delay((int) (rate * .8)); earlyR = new Delay(earlyL.values.length);
            for (int i = 0; i < tapsL.length; i++) {
                double offset = p.earlyDelay + (i + 1) * (5 + p.earlySize * .24);
                tapsL[i] = (int) (rate * (offset + p.pattern * .13 * (i % 3)) / 1000);
                tapsR[i] = (int) (rate * (offset * 1.071 + 1.3) / 1000);
            }
            int count = 4 + (int) Math.min(4, (p.density / 100 + p.quality / 10) * 2);
            combL = new Comb[count]; combR = new Comb[count];
            double damping = -Math.expm1(-2 * Math.PI * Math.min(rate * .45, p.damping * 1000) / rate);
            for (int i = 0; i < count; i++) {
                int frames = (int) (rate * (.023 + .0061 * i) * (.7 + p.size * .006));
                combL[i] = new Comb(frames, rate, p.decay, damping);
                combR[i] = new Comb(frames + (int) (rate * .0017), rate, p.decay, damping);
            }
            double diffusion = .2 + .5 * p.diffusion / 100;
            apL = new Allpass[]{new Allpass((int) (rate * .0047), diffusion), new Allpass((int) (rate * .0017), diffusion)};
            apR = new Allpass[]{new Allpass((int) (rate * .0053), diffusion), new Allpass((int) (rate * .0023), diffusion)};
            toneL = filters(p.tone); toneR = filters(p.tone);
        }
        void tick(double a, double b) {
            double wetL = 0, wetR = 0, erL = 0, erR = 0;
            if (p.early) {
                for (int i = 0; i < tapsL.length; i++) {
                    double weight = (i % 3 == 2 ? -.45 : 1) / (i + 2);
                    erL += earlyL.tap(tapsL[i]) * weight;
                    erR += earlyR.tap(tapsR[i]) * weight;
                }
                earlyL.push(a * .8 + b * .2); earlyR.push(b * .8 + a * .2);
            }
            if (p.late) {
                double delayedL = preL.push(a), delayedR = preR.push(b);
                for (int i = 0; i < combL.length; i++) {
                    wetL += combL[i].tick(delayedL * .8 + delayedR * .2);
                    wetR += combR[i].tick(delayedR * .8 + delayedL * .2);
                }
                wetL /= combL.length; wetR /= combR.length;
                for (int i = 0; i < apL.length; i++) { wetL = apL[i].tick(wetL); wetR = apR[i].tick(wetR); }
            }
            wetL = wetL * p.wet + erL * p.er; wetR = wetR * p.wet + erR * p.er;
            for (int i = 0; i < toneL.length; i++) { wetL = toneL[i].tick(wetL); wetR = toneR[i].tick(wetR); }
            double mid = (wetL + wetR) * .5, side = (wetL - wetR) * .5 * p.width;
            left = a * p.dry + mid + side; right = b * p.dry + mid - side;
        }
    }

    private static Filter[] filters(Coeff[] coefficients) {
        return Arrays.stream(coefficients).map(Filter::new).toArray(Filter[]::new);
    }
    private static double protect(double x, double ceiling) {
        if (!Double.isFinite(x)) return 0;
        // Transparent below 95% of ceiling; bounded soft knee instead of a hard clipped runaway.
        double magnitude = Math.abs(x), knee = ceiling * .95;
        if (magnitude <= knee) return x;
        return Math.copySign(knee + (ceiling - knee) * Math.tanh((magnitude - knee) / (ceiling - knee)), x);
    }
    private static JsonObject object(JsonObject o, String key) {
        JsonElement e = o.get(key); return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }
    private static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key); return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
    private static boolean on(JsonObject o) { return bool(o, "on", false); }
    private static boolean bool(JsonObject o, String key, boolean fallback) {
        try { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException e) { return fallback; }
    }
    private static double value(JsonObject o, String key, double fallback, double min, double max) {
        return number(o.get(key), fallback, min, max);
    }
    private static double positive(JsonObject o, String key, double fallback, double min, double max) {
        double v = number(o.get(key), fallback, -Double.MAX_VALUE, Double.MAX_VALUE);
        return v <= 0 ? fallback : Math.max(min, Math.min(max, v));
    }
    private static double number(JsonElement e, double fallback, double min, double max) {
        try { return Math.max(min, Math.min(max, finite(e == null ? fallback : e.getAsDouble(), fallback))); }
        catch (RuntimeException ex) { return fallback; }
    }
    private static double finite(double value, double fallback) { return Double.isFinite(value) ? value : fallback; }
    private static double db(double value) { return Math.pow(10, value / 20); }
}
