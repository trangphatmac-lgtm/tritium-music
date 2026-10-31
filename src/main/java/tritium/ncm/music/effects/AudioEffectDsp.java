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
 * Portable stereo implementation of the recovered Smart/Master/Emotion parameter protocol.
 * This is NOT the proprietary native binary or a bit-exact emulation. Ten-band EQ uses
 * octave-width RBJ biquads. PEQ types 0..10 follow the recovered native coefficient branches:
 * fixed-Q low/high pass, band pass, notch, fixed-Q low/high shelf, peak, variable-Q
 * low/high shelf and variable-Q low/high pass respectively.
 * Reverb uses a damped Schroeder network and tapped early reflections; stereo enhancement
 * uses mid/side width, delayed decorrelation, and optional side-spectrum shelving. Their
 * scales, reverb tone insert routing and stereo speaker level interpretation, as well as the
 * ALC detector, chorus modulation and stereo speaker fold-down are desktop adaptations
 * where the native implementation is unavailable. An explicit order array is respected;
 * absent it, the previous desktop EQ -> BT -> PEQ -> compressor -> stereo -> reverb order
 * is retained, with new modules inserted and the limiter always last. Delay values are milliseconds,
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
    private volatile DebugSnapshot debugSnapshot;
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
        debugSnapshot = describe(plan, sampleRate);
        current = new Processor(plan, sampleRate);
    }

    public synchronized void setConfiguration(JsonObject config) {
        plan = compile(config, sampleRate);
        debugSnapshot = describe(plan, sampleRate);
        pending.set(new Change(new Processor(plan, sampleRate), true));
    }

    /** Clear filter/reverb history after a discontinuity while retaining the selected plan. */
    public synchronized void reset() { pending.set(new Change(new Processor(plan, sampleRate), false)); }

    /** Compiled configuration, not a loudness measurement. Never inspects mutable PCM state. */
    public DebugSnapshot debugSnapshot() { return debugSnapshot; }

    public record Parameter(String name, double value, String unit) { }
    public record Module(String name, List<Parameter> parameters) {
        public Module { parameters = List.copyOf(parameters); }
    }
    public record DebugSnapshot(double sampleRate, boolean active, List<Module> modules) {
        public DebugSnapshot { modules = List.copyOf(modules); }
    }

    private static Parameter parameter(String name, double value, String unit) {
        return new Parameter(name, value, unit);
    }
    private static double decibels(double amplitude) { return 20 * Math.log10(amplitude); }
    private static double milliseconds(double coefficient, double rate) { return -1000 / (rate * Math.log(coefficient)); }

    private static DebugSnapshot describe(Plan p, double rate) {
        List<Module> modules = new ArrayList<>();
        for (int stage : p.order) {
            modules.add(switch (stage) {
                case EQ -> new Module("eq", p.eqParameters);
                case BT -> new Module("bt", p.btParameters);
                case PEQ -> new Module("peq", List.of(parameter("filters", p.filters[PEQ].length, ""),
                        parameter("gain", decibels(p.gain), "dB")));
                case CMP -> new Module("cmp", List.of(parameter("threshold", p.compressor.threshold, "dB"),
                        parameter("ratio", p.compressor.ratio, ":1"),
                        parameter("attack", milliseconds(p.compressor.attack, rate), "ms"),
                        parameter("release", milliseconds(p.compressor.release, rate), "ms")));
                case CHORUS -> new Module("chorus", List.of(parameter("delay", p.chorus.milliseconds, "ms"),
                        parameter("depth", p.chorus.depth * 100, "%"), parameter("rate", p.chorus.rate, "Hz"),
                        parameter("mix", p.chorus.mix * 100, "%")));
                case STEREO -> new Module("se", List.of(parameter("width", p.stereo.width, "x"),
                        parameter("decorrelation", p.stereo.decorrelation * 100, "%"),
                        parameter("ambience", p.stereo.ambience * 100, "%")));
                case SPATIAL -> new Module("spspatial", List.of(parameter("upmix", p.spatial.upmix ? 1 : 0, ""),
                        parameter("gain", decibels(p.spatial.global), "dB"), parameter("width", p.spatial.virtualWidth, "x")));
                case ECHO -> new Module("dly", List.of(parameter("delay", p.echo.milliseconds, "ms"),
                        parameter("feedback", p.echo.feedback * 100, "%"), parameter("wet", decibels(p.echo.wet), "dB")));
                case REVERB -> new Module("rvb", List.of(parameter("late", p.reverb.late ? 1 : 0, ""),
                        parameter("early", p.reverb.early ? 1 : 0, ""), parameter("predelay", p.reverb.predelay, "ms"),
                        parameter("decay", p.reverb.decay, "s"), parameter("wet", decibels(p.reverb.wet), "dB")));
                case ALC -> new Module("alc", List.of(parameter("points", p.alc.x.length, ""),
                        parameter("attack", milliseconds(p.alc.attack, rate), "ms"),
                        parameter("release", milliseconds(p.alc.release, rate), "ms"),
                        parameter("lookahead", p.alc.lookaheadFrames * 1000.0 / rate, "ms")));
                default -> throw new IllegalStateException("Unknown effect stage " + stage);
            });
        }
        if (p.limiter != null) modules.add(new Module("limiter", List.of(
                parameter("threshold", p.limiter.threshold, "dB"), parameter("output", decibels(p.limiter.outputGain), "dB"),
                parameter("attack", milliseconds(p.limiter.attack, rate), "ms"),
                parameter("release", milliseconds(p.limiter.release, rate), "ms"))));
        return new DebugSnapshot(rate, p.active, modules);
    }

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
    private record Echo(double milliseconds, double feedback, double dry, double wet) {}
    private record Chorus(double milliseconds, double depth, double rate, double mix, int voices) {}
    private record Spatial(boolean upmix, double global, double center, double frontL, double frontR,
                           double left, double right, double virtualWidth) {}
    private record Alc(double[] x, double[] y, double shortCoefficient, double longCoefficient,
                       double attack, double release, int holdFrames, int updateFrames,
                       int lookaheadFrames, int type, double knee) {}
    private record Limiter(double threshold, double attack, double release, double outputGain,
                           double ratio, boolean infinite) {}
    private record Reverb(boolean late, boolean early, double predelay, double decay, double damping,
                          double size, double density, double diffusion, double quality, double width,
                          double earlyDelay, double earlySize, int pattern, double dry, double er,
                          double wet, Coeff[] tone) {}
    private record Plan(Coeff[][] filters, double gain, Compressor compressor, Stereo stereo,
                        Reverb reverb, Echo echo, Chorus chorus, Spatial spatial, Alc alc,
                        int[] order, Limiter limiter, boolean active,
                        List<Parameter> eqParameters, List<Parameter> btParameters) {}
    private static final int EQ = 0, BT = 1, PEQ = 2, CMP = 3, CHORUS = 4, STEREO = 5,
            SPATIAL = 6, ECHO = 7, REVERB = 8, ALC = 9;

    private static Plan compile(JsonObject config, double rate) {
        JsonObject c = config == null ? new JsonObject() : config;
        if (c.has("effect") && c.get("effect").isJsonObject()) c = c.getAsJsonObject("effect");
        if (c.has("aev2") && c.get("aev2").isJsonObject()) c = c.getAsJsonObject("aev2");
        List<Coeff> filters = new ArrayList<>();
        List<Parameter> eqParameters = new ArrayList<>(), btParameters = new ArrayList<>();
        JsonObject eq = object(c, "eq"), bt = object(c, "bt"), peq = object(c, "peq");
        if (on(eq)) {
            JsonArray gains = array(eq, "eqs");
            for (int i = 0; i < Math.min(10, gains.size()); i++) {
                double gain = number(gains.get(i), 0, -24, 24);
                eqParameters.add(parameter((int) EQ_HZ[i] + "Hz", gain, "dB"));
                if (Math.abs(gain) > 1e-8) filters.add(biquad(6, EQ_HZ[i], 1.41421356237, gain, rate));
            }
        }
        Coeff[] eqFilters = filters.toArray(Coeff[]::new); filters.clear();
        if (on(bt)) {
            double bass = value(bt, "bass", 0, -24, 24), treble = value(bt, "treble", 0, -24, 24);
            btParameters.add(parameter("bass", bass, "dB"));
            btParameters.add(parameter("treble", treble, "dB"));
            if (bass != 0) filters.add(biquad(7, positive(bt, "bassFreq", 120, 20, rate * .45),
                    positive(bt, "bassQ", .7071, .1, 10), bass, rate));
            if (treble != 0) filters.add(biquad(8, positive(bt, "trebleFreq", 6000, 20, rate * .45),
                    positive(bt, "trebleQ", .7071, .1, 10), treble, rate));
        }
        Coeff[] btFilters = filters.toArray(Coeff[]::new); filters.clear();
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
                slots[index] = on(band) && type >= 0 && type <= 10
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
        JsonObject dly = object(c, "dly"), ch = object(c, "chorus"), sp = object(c, "spspatial");
        Echo echo = on(dly) ? new Echo(value(dly, "delay", 0, 0, 5000),
                value(dly, "feedback", 0, 0, 1), db(value(dly, "dry", 0, -96.3, 6)),
                db(value(dly, "wet", -12, -96.3, 6))) : null;
        Chorus chorus = on(ch) ? new Chorus(value(ch, "delay", 15, 0, 100),
                value(ch, "depth", .4, 0, 1), value(ch, "rate", .3, 0, 20),
                value(ch, "mix", .25, 0, 1), (int) value(ch, "voices", 2, 1, 8)) : null;
        Spatial spatial = on(sp) ? new Spatial(value(sp, "upmix", 1, 0, 2) != 0,
                db(value(sp, "global", 0, -24, 12)), db(value(sp, "center", 0, -24, 12)),
                db(value(sp, "fl", 0, -24, 12)), db(value(sp, "fr", 0, -24, 12)),
                db(value(sp, "left", 0, -24, 12)), db(value(sp, "right", 0, -24, 12)),
                value(sp, "lronoff", 0, 0, 1) == 0 ? 1 : 1 + Math.sin(Math.toRadians(value(sp, "lrangle", 10, 0, 90))) * .5) : null;
        Alc alc = compileAlc(object(c, "alc"), rate);
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
                    value(late, "pdelay", 20, 0, 500), value(late, "dtime", 1, 0, 10),
                    positive(late, "hfdamping", 6, .1, 20), value(late, "rshape", 50, 0, 100),
                    value(late, "density", 60, 0, 100), value(late, "diffusion", 60, 0, 100),
                    value(late, "q", 6, 1, 10), value(late, "swidth", 100, 0, 200) / 100,
                    value(er, "sdelay", 20, 0, 500), value(er, "rsize", 50, 0, 100),
                    (int) value(er, "pattern", 0, 0, 20), db(value(levels, "dry", 0, -96.3, 6)),
                    db(value(levels, "er", -24, -96.3, 6)), db(value(levels, "rvb", -24, -96.3, 6)),
                    tone.toArray(Coeff[]::new));
        }
        JsonObject limiter = object(c, "limiter");
        Limiter limit = on(limiter) ? new Limiter(value(limiter, "threshold", 0, -36, 0),
                Math.exp(-1 / (rate * positive(limiter, "attack", .002, .0001, .5))),
                Math.exp(-1 / (rate * positive(limiter, "release", .04, .005, 5))),
                db(value(limiter, "outputgain", 0, -36, 12)), value(limiter, "ratio", 4, 1, 40),
                bool(limiter, "infiniteRatio", true)) : null;
        boolean[] enabled = {eqFilters.length > 0, btFilters.length > 0, !filters.isEmpty() || gain != 1,
                compressor != null, chorus != null, stereo != null, spatial != null, echo != null, reverb != null, alc != null};
        int[] order = compileOrder(array(c, "order"), enabled);
        return new Plan(new Coeff[][]{eqFilters, btFilters, filters.toArray(Coeff[]::new)}, gain,
                compressor, stereo, reverb, echo, chorus, spatial, alc, order, limit, order.length > 0 || limit != null,
                List.copyOf(eqParameters), List.copyOf(btParameters));
    }

    /** Includes the active modules of all 64 bundled emotion templates. */
    static boolean supportsModule(String name) { return stageIndex(name) >= 0 || "limiter".equals(name); }
    private static int stageIndex(String name) {
        return switch (name) {
            case "eq" -> EQ; case "bt" -> BT; case "peq" -> PEQ; case "cmp" -> CMP;
            case "chorus" -> CHORUS; case "se" -> STEREO; case "spspatial" -> SPATIAL;
            case "dly" -> ECHO; case "rvb" -> REVERB; case "alc" -> ALC; default -> -1;
        };
    }
    private static int[] compileOrder(JsonArray requested, boolean[] enabled) {
        int[] stages = new int[enabled.length]; boolean[] seen = new boolean[enabled.length]; int count = 0;
        for (JsonElement entry : requested) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) continue;
            int stage = stageIndex(entry.getAsString());
            if (stage >= 0 && enabled[stage] && !seen[stage]) { stages[count++] = stage; seen[stage] = true; }
        }
        // An order may omit disabled or newly introduced modules. Append active omissions,
        // without accidentally processing a repeated module twice or dropping it entirely.
        for (int stage = 0; stage < enabled.length; stage++)
            if (enabled[stage] && !seen[stage]) stages[count++] = stage;
        return Arrays.copyOf(stages, count);
    }
    private static Alc compileAlc(JsonObject a, double rate) {
        if (!on(a)) return null;
        JsonArray points = array(a, "xypoints");
        var curve = new java.util.TreeMap<Double, Double>();
        for (int i = 0; i + 1 < Math.min(32, points.size()); i += 2)
            curve.put(number(points.get(i), -200, -200, 12), number(points.get(i + 1), -200, -200, 12));
        if (curve.size() < 2) { curve.clear(); curve.put(-200.0, -200.0); curve.put(0.0, 0.0); }
        double[] x = new double[curve.size()], y = new double[curve.size()]; int index = 0;
        for (var point : curve.entrySet()) { x[index] = point.getKey(); y[index++] = point.getValue(); }
        return new Alc(x, y, Math.exp(-1 / (rate * positive(a, "st", .02, .0001, 2))),
                Math.exp(-1 / (rate * positive(a, "lt", .5, .001, 10))),
                Math.exp(-1 / (rate * positive(a, "attack", .005, .0001, 2))),
                Math.exp(-1 / (rate * positive(a, "release", .1, .001, 10))),
                (int) (rate * value(a, "hold", .05, 0, 2)), (int) value(a, "minisize", 64, 1, 4096),
                (int) (rate * value(a, "lookahead", 0, 0, .5)), (int) value(a, "type", 0, 0, 2),
                value(a, "xyknee", 0, 0, 24));
    }

    /** RBJ normalized biquads, with independent transposed-direct-form state per channel. */
    private static Coeff biquad(int type, double hz, double q, double gain, double rate) {
        // libneaudioeffects.so: unsigned-short jump table at 0x13f624, dispatch 0x97e1c.
        // Types 4/5 (0x97fd8/0x9807c) use sqrt(2*A)*sin(w), i.e. fixed Q=1/sqrt(2).
        if (type == 0 || type == 1 || type == 4 || type == 5) q = Math.sqrt(.5);
        if (type == 4) type = 7;
        if (type == 5) type = 8;
        double w = 2 * Math.PI * Math.max(10, Math.min(rate * .45, hz)) / rate;
        double cos = Math.cos(w), sin = Math.sin(w), a = Math.pow(10, gain / 40), alpha = sin / (2 * q);
        double b0, b1, b2, a0, a1, a2;
        if (type == 0 || type == 9 || type == 1 || type == 10) {
            boolean highpass = type == 1 || type == 10;
            b0 = (1 + (highpass ? cos : -cos)) * .5;
            b1 = (highpass ? -2 : 2) * b0; b2 = b0;
            a0 = 1 + alpha; a1 = -2 * cos; a2 = 1 - alpha;
        } else if (type == 2 || type == 3) {
            b0 = type == 2 ? alpha : 1; b1 = type == 2 ? 0 : -2 * cos; b2 = type == 2 ? -alpha : 1;
            a0 = 1 + alpha; a1 = -2 * cos; a2 = 1 - alpha;
        } else if (type == 7 || type == 8) {
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
        final Filter[][] l, r;
        final Room room;
        final EchoProcessor echo;
        final ChorusProcessor chorus;
        final AlcProcessor alc;
        final Delay decorrelation;
        final Filter[] spectrumShape;
        double envelope, limitEnvelope, left, right;
        Processor(Plan p, double rate) {
            this.p = p;
            l = new Filter[][]{filters(p.filters[0]), filters(p.filters[1]), filters(p.filters[2])};
            r = new Filter[][]{filters(p.filters[0]), filters(p.filters[1]), filters(p.filters[2])};
            room = p.reverb == null ? null : new Room(p.reverb, rate);
            echo = p.echo == null ? null : new EchoProcessor(p.echo, rate);
            chorus = p.chorus == null ? null : new ChorusProcessor(p.chorus, rate);
            alc = p.alc == null ? null : new AlcProcessor(p.alc);
            decorrelation = p.stereo == null ? null : new Delay((int) (rate * .013));
            // The original spectrumShaper coefficients are unknown. Keep low frequencies
            // focused and add gentle high-frequency spread instead of introducing distortion.
            spectrumShape = p.stereo != null && p.stereo.shape
                    ? filters(new Coeff[]{biquad(7, 180, .7071, -3, rate), biquad(8, 4500, .7071, 1.5, rate)})
                    : new Filter[0];
        }
        void tick(double inL, double inR) {
            if (!p.active) { left = inL; right = inR; return; }
            double a = inL, b = inR;
            for (int stage : p.order) {
                switch (stage) {
                    case EQ, BT, PEQ -> {
                        if (stage == PEQ) { a *= p.gain; b *= p.gain; }
                        for (int i = 0; i < l[stage].length; i++) { a = l[stage][i].tick(a); b = r[stage][i].tick(b); }
                    }
                    case CMP -> {
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
                    case CHORUS -> { chorus.tick(a, b); a = chorus.left; b = chorus.right; }
                    case STEREO -> {
                        Stereo s = p.stereo;
                        double mid = (a + b) * .5, side = (a - b) * .5;
                        double delayed = decorrelation.push(mid);
                        double spread = side * s.width + (mid - delayed) * s.decorrelation + delayed * s.ambience;
                        for (Filter filter : spectrumShape) spread = filter.tick(spread);
                        a = mid + spread; b = mid - spread;
                    }
                    case SPATIAL -> {
                        Spatial s = p.spatial;
                        double mid = (a + b) * .5, side = (a - b) * .5 * s.virtualWidth;
                        // Stereo fold-down: an extracted coherent centre plus residual fronts.
                        // Zero-dB controls reconstruct the input exactly. Rear/height/LFE
                        // paths are inactive in every bundled stereo emotion template.
                        a = (s.upmix ? mid * s.center + side * s.frontL : (mid + side) * s.frontL) * s.global * s.left;
                        b = (s.upmix ? mid * s.center - side * s.frontR : (mid - side) * s.frontR) * s.global * s.right;
                    }
                    case ECHO -> { echo.tick(a, b); a = echo.left; b = echo.right; }
                    case REVERB -> { room.tick(a, b); a = room.left; b = room.right; }
                    case ALC -> { alc.tick(a, b); a = alc.left; b = alc.right; }
                    default -> throw new AssertionError("Uncompiled DSP stage");
                }
            }
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

    /** A causal, stereo-independent echo. Unity feedback is bounded inside the loop. */
    private static final class EchoProcessor {
        final Echo p;
        final Delay l, r;
        final boolean zeroDelay;
        double left, right;
        EchoProcessor(Echo p, double rate) {
            this.p = p; zeroDelay = p.milliseconds == 0;
            int frames = Math.max(1, (int) Math.round(rate * p.milliseconds / 1000));
            l = new Delay(frames); r = new Delay(frames);
        }
        void tick(double a, double b) {
            if (zeroDelay) { left = a * (p.dry + p.wet); right = b * (p.dry + p.wet); return; }
            double delayedL = l.values[l.position], delayedR = r.values[r.position];
            // High input or feedback=1 cannot leave an infinite/overflowing delay state.
            l.push(protect(a + delayedL * p.feedback, 16));
            r.push(protect(b + delayedR * p.feedback, 16));
            left = a * p.dry + delayedL * p.wet;
            right = b * p.dry + delayedR * p.wet;
        }
    }

    /** Fractional-delay chorus; per-voice sine oscillators avoid per-sample trigonometry. */
    private static final class ChorusProcessor {
        final Chorus p;
        final Delay l, r;
        final double[] sine, cosine;
        final double stepSin, stepCos, delayFrames, depthFrames;
        double left, right;
        int renormalize;
        ChorusProcessor(Chorus p, double rate) {
            this.p = p; delayFrames = Math.max(1, rate * p.milliseconds / 1000);
            depthFrames = Math.min(delayFrames - 1, rate * .005 * p.depth);
            l = new Delay((int) Math.ceil(delayFrames + depthFrames) + 2); r = new Delay(l.values.length);
            sine = new double[p.voices]; cosine = new double[p.voices];
            for (int i = 0; i < p.voices; i++) {
                double phase = 2 * Math.PI * i / p.voices;
                sine[i] = Math.sin(phase); cosine[i] = Math.cos(phase);
            }
            double step = 2 * Math.PI * p.rate / rate;
            stepSin = Math.sin(step); stepCos = Math.cos(step);
        }
        void tick(double a, double b) {
            double wetL = 0, wetR = 0;
            for (int i = 0; i < sine.length; i++) {
                wetL += l.fractionalTap(delayFrames + depthFrames * sine[i]);
                wetR += r.fractionalTap(delayFrames + depthFrames * cosine[i]);
                double oldSin = sine[i];
                sine[i] = oldSin * stepCos + cosine[i] * stepSin;
                cosine[i] = cosine[i] * stepCos - oldSin * stepSin;
            }
            if (++renormalize == 4096) {
                renormalize = 0;
                for (int i = 0; i < sine.length; i++) {
                    double norm = Math.hypot(sine[i], cosine[i]); sine[i] /= norm; cosine[i] /= norm;
                }
            }
            l.push(a); r.push(b);
            left = a * (1 - p.mix) + wetL * p.mix / p.voices;
            right = b * (1 - p.mix) + wetR * p.mix / p.voices;
        }
    }

    /** Linked adaptive level control using the recovered dB input/output curve. */
    private static final class AlcProcessor {
        final Alc p;
        final Delay delayL, delayR;
        double shortPower, longPower, held, gain = 1, target = 1, left, right;
        int holdRemaining, updateRemaining;
        AlcProcessor(Alc p) {
            this.p = p;
            delayL = p.lookaheadFrames > 0 ? new Delay(p.lookaheadFrames) : null;
            delayR = p.lookaheadFrames > 0 ? new Delay(p.lookaheadFrames) : null;
        }
        void tick(double a, double b) {
            // Keep hostile/overflowing upstream samples out of persistent detector history.
            // This bound is far above any audio level reachable by the bundled templates.
            double detectorL = Math.max(-1e6, Math.min(1e6, finite(a, 0)));
            double detectorR = Math.max(-1e6, Math.min(1e6, finite(b, 0)));
            double peak = Math.max(Math.abs(detectorL), Math.abs(detectorR));
            double power = (detectorL * detectorL + detectorR * detectorR) * .5;
            shortPower = p.shortCoefficient * shortPower + (1 - p.shortCoefficient) * power;
            longPower = p.longCoefficient * longPower + (1 - p.longCoefficient) * power;
            double level = p.type == 2 ? peak : Math.sqrt(p.type == 1 ? longPower : Math.max(shortPower, longPower));
            if (level >= held) { held = level; holdRemaining = p.holdFrames; }
            else if (holdRemaining > 0) holdRemaining--;
            else held = level;
            if (updateRemaining-- <= 0) {
                double inDb = Math.max(-200, 20 * Math.log10(Math.max(1e-10, held)));
                target = db(Math.max(-60, Math.min(24, curve(inDb) - inDb)));
                updateRemaining = p.updateFrames - 1;
            }
            double coefficient = target < gain ? p.attack : p.release;
            gain = coefficient * gain + (1 - coefficient) * target;
            left = (delayL == null ? a : delayL.push(a)) * gain;
            right = (delayR == null ? b : delayR.push(b)) * gain;
        }
        double curve(double value) {
            if (p.knee == 0) return linear(value);
            // Smooth a piecewise-linear curve over the requested knee width. Bundled
            // templates use knee=0; this bounded averaging is a desktop extension.
            return (linear(value - p.knee * .5) + 2 * linear(value) + linear(value + p.knee * .5)) * .25;
        }
        double linear(double value) {
            if (value <= p.x[0]) return p.y[0] + value - p.x[0];
            for (int i = 1; i < p.x.length; i++) if (value <= p.x[i]) {
                double mix = (value - p.x[i - 1]) / (p.x[i] - p.x[i - 1]);
                return p.y[i - 1] + mix * (p.y[i] - p.y[i - 1]);
            }
            int last = p.x.length - 1;
            return p.y[last] + value - p.x[last];
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
        double fractionalTap(double frames) {
            double bounded = Math.max(1, Math.min(values.length - 1, frames));
            int whole = (int) bounded;
            double fraction = bounded - whole;
            double a = tap(whole), b = tap(whole + 1);
            return a + (b - a) * fraction;
        }
    }
    private static final class Comb {
        final Delay delay;
        final double feedback, damping;
        double low;
        Comb(int frames, double rate, double decay, double damping) {
            delay = new Delay(frames); feedback = decay <= 0 ? 0 : Math.pow(10, -3 * frames / (rate * decay));
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
                double delayedL = p.predelay == 0 ? a : preL.push(a), delayedR = p.predelay == 0 ? b : preR.push(b);
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
