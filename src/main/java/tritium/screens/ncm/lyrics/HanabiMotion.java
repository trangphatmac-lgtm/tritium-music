// SPDX-License-Identifier: AGPL-3.0-only
// Folia hanabi/text/animations.ts: playback-time poses, without a JavaScript runtime.
package tritium.screens.ncm.lyrics;

import static java.lang.Math.*;
import static tritium.screens.ncm.lyrics.LyricMath.*;

final class HanabiMotion {
    static final class Pose {
        double x, y, sx=1, sy=1, rotation, opacity=1, skew, rgb, blur, split, reveal=1, flash, glow, tint;
        int scramble=-1;
        void scale(double value) { sx=sy=value; }
        void add(Pose b) {
            x+=b.x; y+=b.y; sx*=b.sx; sy*=b.sy; rotation+=b.rotation; opacity*=b.opacity;
            skew+=b.skew; rgb+=b.rgb; blur=max(blur,b.blur); split=max(split,b.split); reveal=min(reveal,b.reveal);
            flash=max(flash,b.flash); glow=max(glow,b.glow); tint=max(tint,b.tint);
        }
    }
    static double duration(String fx) {
        return switch(fx) {
            case "pop" -> .45; case "drop","spin" -> .62; case "wipe" -> .42; case "type" -> .12;
            case "scramble","wave","burst" -> .75; case "stretch","flip" -> .55;
            case "elastic","spiral" -> .85; case "split","stamp" -> .5; default -> .6;
        };
    }
    private static double back(double q,double s) { return 1+(s+1)*pow(q-1,3)+s*pow(q-1,2); }
    private static double inBack(double q,double s) { return (s+1)*q*q*q-s*q*q; }
    private static double expo(double q) { return q>=1?1:1-pow(2,-10*q); }
    private static double dampCos(double q,double f,double d) { return exp(-d*q)*cos(f*q)*(1-q); }
    private static double dampSin(double q,double f,double d) { return exp(-d*q)*sin(f*q)*(1-q); }
    private static double hash(int a,int b) {
        int h=(a^0x9e3779b9)*0x85ebca6b ^ (b+0x632be5ab)*0xc2b2ae35;
        h^=h>>>15; h*=0x2c1b3c6d; h^=h>>>12; h*=0x297a2d39; h^=h>>>15;
        return Integer.toUnsignedLong(h)/4294967296.0;
    }
    static Pose enter(String fx,double progress,double time,double seed,double centerX,double centerY,boolean vertical) {
        var p=new Pose(); if(progress>=1)return p;
        double q=clamp(progress),r0=random(seed),r1=random(seed+1),r2=random(seed+2),dx=vertical?0:1,dy=vertical?1:0;
        switch(fx) {
            case "pop" -> {double s=back(q,2.4);p.sx=s*(1-.1*dampSin(q,14,3));p.sy=s*(1+.14*dampSin(q,14,3));p.rotation=(r0-.5)*.7*(1-out(q));p.y=.25*(1-out(q));p.opacity=clamp(q*6);}
            case "drop" -> {
                if(q<.48){double f=q/.48;p.y=-2.4*(1-f*f);p.sy=1+.3*f;p.sx=1-.12*f;}
                else {double f=(q-.48)/.52;p.y=-.32*abs(sin(f*PI*2))*exp(-3*f)*(1-f);p.sy=1-.3*dampCos(f,16,4);p.sx=1+.22*dampCos(f,16,4);}
                p.opacity=clamp(q*7);
            }
            case "rise" -> {
                double e=out(q/.58);p.y=1.7*(1-e);
                if(q<.58){p.sy=1+.55*(1-e);p.sx=1-.28*(1-e);}
                else {double f=(q-.58)/.42;p.sy=1-.2*dampSin(f,11,2.5);p.sx=1+.16*dampSin(f,11,2.5);p.y+=.08*dampSin(f,11,2.5);}
                p.opacity=clamp(q*4);
            }
            case "spin" -> {double e=back(q,1.9);p.rotation=-2.6*(1-e)*(r0<.5?1:-1);p.scale(.15+.85*e);p.x=-.7*(1-out(q))*dx;p.y=-.7*(1-out(q))*dy-.3*sin(PI*q)*(1-q);p.opacity=clamp(q*4);}
            case "wipe" -> {double e=out(q);p.reveal=out(q/.8);p.x=-.35*(1-e)*dx;p.y=-.35*(1-e)*dy;p.skew=-.3*(1-e);p.opacity=clamp(q*8);}
            case "type" -> {p.opacity=q>0?1:0;p.scale(1+.3*pow(1-clamp(q/.6),2));}
            case "scramble" -> {
                p.opacity=clamp(q*8);
                if(q<.72){int frame=(int)floor(time*22);p.scramble=(int)(hash(frame,(int)(r0*1e6))*1e6);p.y=(hash(frame+7,(int)(r1*1e6))-.5)*.12;p.opacity*=.75+.25*hash(frame+13,1);}
                else p.scale(1+.16*pow(1-(q-.72)/.28,2));
            }
            case "stretch" -> {double k=pow(1-q,3);p.sx=1+2*k-.16*dampSin(q,13,3);p.sy=1-.72*k+.12*dampSin(q,13,3);p.x=-1.4*k*dx;p.y=-1.4*k*dy;p.opacity=clamp(q*5);}
            case "elastic" -> {double angle=r0*PI*2,spring=exp(-4.2*q)*cos(q*PI*4.5)*(1-q);p.x=cos(angle)*1.6*spring;p.y=sin(angle)*1.6*spring;p.rotation=(r1-.5)*.8*spring;p.sx=1+.18*abs(spring);p.sy=1-.12*abs(spring);p.opacity=clamp(q*5);}
            case "split" -> {p.split=1-expo(clamp(q/.8));if(q>.8){double f=(q-.8)/.2;p.sx=1+.1*dampSin(f,10,2);p.sy=1-.08*dampSin(f,10,2);}p.opacity=clamp(q*5);}
            case "spiral" -> {double e=out(q),radius=3.4*(1-e),a=r0*PI*2+PI*2.5*(1-e);p.x=cos(a)*radius;p.y=sin(a)*radius*.8;p.rotation=-PI*2*(1-e)*(r1<.5?1:-1);p.scale(.25+.75*back(q,1.5));p.opacity=clamp(q*3);}
            case "flip" -> {double phi=PI*2.5*(1-out(q));p.sx=cos(phi);p.sy=1+.08*sin(phi);p.y=-.3*sin(PI*q)*(1-q);p.opacity=clamp(q*6);}
            case "glitch" -> {
                if(q<.8){int frame=(int)floor(time*24),s=(int)(r0*1e6);double k=1-q/.8,h1=hash(frame,s),h2=hash(frame,s+1),h3=hash(frame,s+2),h4=hash(frame,s+3);
                    p.x=h2>.35?(h1-.5)*.9*k:0;p.rgb=(.05+.13*h3)*k;p.skew=h4>.7?(h1-.5)*.9*k:0;p.sx=h2>.82?1+.4*k:1;p.opacity=clamp(q*10)*(hash(frame,s+4)>.18?1:.25);}
            }
            case "stamp" -> {
                if(q<.5){double f=q/.5;p.scale(2.6-1.6*f*f);p.rotation=(r0-.5)*.35*(1-f);p.opacity=clamp(f*2.2);}
                else {double f=(q-.5)/.5;p.sx=1+.22*dampCos(f,17,4);p.sy=1-.22*dampCos(f,17,4);}
            }
            case "wave" -> {double k=pow(1-q,1.5);p.y=1.1*k*cos(q*PI*3);p.rotation=.4*k*sin(q*PI*3);p.sy=1+.18*k*sin(q*PI*3+.6);p.opacity=clamp(q*4);}
            case "zoom" -> {double k=1.8*(1-expo(q));p.scale(1+k*1.5);p.x=-centerX*k;p.y=-centerY*k;p.blur=pow(1-q,1.4);p.opacity=clamp(q*3.5);}
            case "burst" -> {double e=back(q,1.8);p.x=centerX*(1-e);p.y=centerY*(1-e);p.scale(.2+.8*back(clamp(q*1.3),1.6));p.rotation=(r2-.5)*4*(1-out(q));p.opacity=clamp(q*6);}
            default -> { }
        }
        return p;
    }
    static Pose exit(String fx,double progress,double time,double seed,double centerX,double centerY,boolean vertical) {
        var p=new Pose(); if(progress<=0)return p;
        double q=clamp(progress),r0=random(seed),r1=random(seed+1),r2=random(seed+2),dx=vertical?0:1,dy=vertical?1:0;
        switch(fx) {
            case "ascend" -> {double e=q*q*q,dip=q<.22?sin(q/.22*PI)*.12:0;p.y=dip-2.8*e;p.x=(r0-.5)*.7*e;p.rotation=(r1-.5)*.5*e;p.sy=1+.45*e;p.sx=1-.22*e;p.opacity=1-q*q;}
            case "scatter" -> {double ox=-centerX+(r0-.5)*2,oy=-centerY+(r1-.5)*2,len=max(.001,hypot(ox,oy)),e=out(q),dist=2.4+r2*1.8;p.x=ox/len*dist*e;p.y=oy/len*dist*e;p.rotation=(r1-.5)*3.2*e;p.scale(1-.65*e);p.opacity=1-pow(q,1.6);}
            case "shrink" -> {double s=q<.2?1+.16*sin(q/.2*PI/2):1.16*(1-inBack((q-.2)/.8,1.4));p.scale(max(0,s));p.rotation=(r0-.5)*.9*q;p.opacity=q>.8?(1-q)/.2:1;}
            case "slide" -> {double e=inBack(q,1.6);p.x=dx*7*e;p.y=dy*7*e;p.sx=1+.45*q*q*abs(dx);p.sy=1+.45*q*q*abs(dy);p.skew=-.45*q*q;p.opacity=1-pow(clamp((q-.45)/.55),1.5);}
            case "dissolve" -> {double flicker=hash((int)floor(time*20),(int)(r0*1e6));p.opacity=pow(1-q,1.3)*(1-.45*q*flicker);p.scale(1+.14*(1-(1-q)*(1-q)));p.y=-.35*(1-(1-q)*(1-q));p.blur=q;}
            case "fall" -> {double e=q*q;p.y=3*e;p.x=.28*sin(q*PI*2+r0*6)*q;p.rotation=(r1-.5)*1.2*e;p.sy=1+.4*e;p.sx=1-.25*e;p.opacity=1-pow(q,1.6);}
            case "blink" -> {boolean on=q<.14||(q>=.24&&q<.4)||(q>=.5&&q<.6)||(q>=.7&&q<.76)||(q>=.82&&q<.85);p.opacity=on&&q<1?1:0;p.scale(1+(on?.05*q:0));}
            default -> { }
        }
        if(q>=1)p.opacity=0;return p;
    }
    static Pose sing(String fx,double u,double duration,double seed) {
        var p=new Pose(); if(u<0)return p;
        double hold=max(duration,.12),envelope=clamp(u/.08)*(1-clamp((u-hold)/.35)),rand=random(seed);
        switch(fx) {
            case "bounce" -> {
                if(u<.3)p.y=-.26*sin(u/.3*PI);else if(u<.46)p.y=-.06*sin((u-.3)/.16*PI);
                double land=u-.3;if(land>=0&&land<.3){double f=land/.3;p.sy=1-.14*dampCos(f,14,3);p.sx=1+.1*dampCos(f,14,3);}else if(u<.3)p.sy=1+.08*sin(u/.3*PI);
            }
            case "flash" -> {p.flash=exp(-u/.16);p.scale(1+.2*exp(-u/.1)*clamp(u/.03));}
            case "swell" -> p.scale(1+.24*back(clamp(u/.18),2)*(1-clamp((u-hold)/.35)));
            case "shake" -> {double k=clamp(u/.04)*exp(-max(0,u-hold)/.08)*(u<hold+.4?1:0);p.x=.06*sin(u*61+rand*9)*k;p.y=.04*sin(u*47+rand*5)*k;p.rotation=.08*sin(u*53+rand*7)*k;}
            case "jump" -> {if(u<.42){double f=u/.42;p.y=-.5*sin(f*PI);p.rotation=(rand<.5?1:-1)*.22*sin(f*PI*2)*(1-f);p.sy=1+.2*sin(f*PI)*(1-f);p.sx=1-.12*sin(f*PI)*(1-f);}else if(u<.77){double f=(u-.42)/.35;p.sy=1-.2*dampCos(f,13,3);p.sx=1+.16*dampCos(f,13,3);}}
            case "glow" -> {p.glow=envelope;p.flash=.35*envelope;p.scale(1+.07*envelope);}
            case "underline" -> p.y=-.05*envelope;
            case "color" -> {p.tint=clamp(u/.1);p.scale(1+.14*exp(-u/.12)*clamp(u/.03));}
            default -> { }
        }
        return p;
    }
}
