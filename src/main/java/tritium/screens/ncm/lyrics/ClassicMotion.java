// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Folia classic/GlowWord defaults, sampled analytically so pause and seeks are deterministic. */
final class ClassicMotion {
    enum Profile {
        NORMAL(.15, .3, .2, .8, .9, 20, 40),
        FAST(.08, .16, .12, .24, .22, 18, 32),
        INSTANT(.03, .12, .08, .12, .12, 14, 24);
        final double lookahead, exit, reveal, recolor, afterglow, inner, outer;
        Profile(double lookahead, double exit, double reveal, double recolor, double afterglow, double inner, double outer) {
            this.lookahead=lookahead; this.exit=exit; this.reveal=reveal; this.recolor=recolor;
            this.afterglow=afterglow; this.inner=inner; this.outer=outer;
        }
        static Profile of(LyricTimeline.Line line) {
            double duration=line.end()-line.start();
            return duration<.1 ? INSTANT : duration<.18 ? FAST : NORMAL;
        }
    }
    record Pose(double x, double y, double scale, double angle, double opacity, double highlight, double blur) { }
    record LinePose(double scale, double opacity, double blur) { }

    // CSS easeInOut (.42,0,.58,1), shared by the original body and halo keyframes.
    static double ease(double t) {
        t=clamp(t); if(t==0||t==1)return t;
        double low=0, high=1, u=t;
        for(int i=0;i<16;i++) {
            double x=3*(1-u)*(1-u)*u*.42+3*(1-u)*u*u*.58+u*u*u;
            if(x<t) low=u; else high=u;
            u=(low+high)/2;
        }
        return u*u*(3-2*u);
    }
    static double spring(double seconds) {
        double t=Math.max(0,seconds);
        return 1-Math.exp(-10*t)*(Math.cos(10*t)+Math.sin(10*t));
    }
    static double renderEnd(LyricTimeline.Line line) {
        Profile p=Profile.of(line); double duration=line.end()-line.start();
        double last=line.glyphs().isEmpty()?line.end():line.glyphs().getLast().end();
        if(p==Profile.INSTANT) return Math.max(line.end(),line.start()+.067);
        double exit=p==Profile.NORMAL?Math.min(.32,Math.max(.18,duration*.18)):Math.min(.04,Math.max(.03,duration*.22));
        return Math.max(line.end(),last+(p==Profile.NORMAL?.06:.03)+exit);
    }
    static double activeEnd(LyricWords.Segment word, Profile p, double renderEnd) {
        return switch(p) {
            case NORMAL -> word.end();
            case FAST -> Math.min(renderEnd,Math.max(word.end(),word.start()+.12));
            case INSTANT -> renderEnd;
        };
    }
    static Pose word(ClassicLayout.Word word, Profile p, double time, double renderEnd) {
        double start=word.segment().start()-p.lookahead, end=activeEnd(word.segment(),p,renderEnd);
        double age=Math.max(0,Math.min(time,end)-start), enter=spring(age);
        double passed=Math.max(0,time-end), settle=ease(passed/.5);
        double x=word.dx()+Math.sin(word.dy())*100*(1-enter)*(1-settle);
        double y=word.dy()+Math.cos(word.dx())*50*(1-enter)*(1-settle);
        double scale=mix(mix(.5,word.scale()*1.4,enter),word.scale(),settle);
        double angle=mix(word.angle()+20,word.angle(),enter);
        angle=mix(angle,word.angle()+word.passedAngle(),clamp(passed/5));
        double duration=Math.max(end-word.segment().start(),p==Profile.NORMAL?.1:p==Profile.FAST?.12:.08);
        return new Pose(x,y,scale,angle,
                time<start?0:mix(clamp(age/.1),.82,settle),
                clamp(age/duration)*(1-ease(passed/p.recolor)),
                10*(1-ease(age/p.reveal))*(1-settle));
    }
    static double glow(LyricWords.Segment word, LyricTimeline.Glyph glyph, Profile p, double time, double renderEnd) {
        double end=activeEnd(word,p,renderEnd), sample=Math.min(time,end);
        double duration=Math.max(end-word.start(),p==Profile.NORMAL?.1:p==Profile.FAST?.12:.08);
        double start=word.start()-p.lookahead, peak=.3;
        boolean sustained=p==Profile.NORMAL&&word.glyphs().size()==1;
        if(p==Profile.NORMAL&&!sustained) { start=glyph.start()-p.lookahead; duration=Math.max(.001,glyph.end()-glyph.start())*6; }
        else if(p==Profile.FAST) {duration=Math.min(.2,Math.max(.12,duration));peak=.4;}
        else if(p==Profile.INSTANT) {duration=Math.min(.12,duration);peak=.35;}
        else peak=.9;
        double t=(sample-start)/duration;
        double value=t<0?0:t<peak?ease(t/peak):sustained?1:1-ease((t-peak)/(1-peak));
        return value*(1-out((time-end)/p.afterglow));
    }
    static LinePose line(LyricTimeline.Line line, double time, double exitAt) {
        Profile p=Profile.of(line);
        double enter=p==Profile.INSTANT?1:out((time-line.start())/(p==Profile.FAST?.16:.3));
        double exit=ease((time-exitAt)/p.exit);
        double initial=p==Profile.NORMAL?.9:p==Profile.FAST?.96:1;
        double target=p==Profile.NORMAL?1.1:p==Profile.FAST?1.04:1.02;
        return new LinePose(mix(mix(initial,1,enter),target,exit),
                mix(p==Profile.FAST?.35:0,1,enter)*(1-exit),
                mix((p==Profile.NORMAL?10:4)*(1-enter),p==Profile.NORMAL?20:p==Profile.FAST?10:6,exit));
    }
    static double breathing(double time, boolean scale) {
        double phase=Math.max(0,time)%7/7*4; int i=(int)phase;
        double[] keys=scale?new double[]{1,1.01,1,.995,1}:new double[]{0,-14,0,6.3,0};
        return mix(keys[i],keys[i+1],ease(phase-i));
    }
}
