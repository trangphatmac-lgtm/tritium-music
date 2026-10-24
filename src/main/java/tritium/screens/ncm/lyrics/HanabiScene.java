// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import com.google.gson.*;
import org.lwjgl.opengl.GL11;
import org.lwjgl.BufferUtils;
import java.util.*;
import static tritium.screens.ncm.lyrics.FoliaData.*;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Hanabi's catalog, analytic damped fireworks, rising camera and kinetic letter blocks. */
final class HanabiScene implements LyricScene {
    private static final int[] COLORS={0xffffb5d7,0xffffdf8b,0xff9bd8ff,0xffc8b6ff,0xfffff1cc};
    private record Spark(double x,double y,double vx,double vy,double drag,double fall,double start,double life,
                         double size,int color,int endColor,double shift,int trailColor,double trail,int segments,
                         double fade,double strobe,double strobeFrom,double twinkle,double glitter,
                         double spiral,double spin,double phase,double depth,int tex) {
        static Spark read(JsonObject s){return new Spark(n(s,"x0",0),n(s,"y0",0),n(s,"vx",0),n(s,"vy",0),n(s,"drag",1),
                n(s,"fall",0),n(s,"start",0),n(s,"life",1),n(s,"size",2),(int)n(s,"color",0xffffff)|0xff000000,
                (int)n(s,"endColor",0xffffff)|0xff000000,n(s,"shift",1),(int)n(s,"trailColor",0xffffff)|0xff000000,
                n(s,"trail",0),(int)n(s,"segments",1),n(s,"fadeTail",.4),n(s,"strobe",0),n(s,"strobeFrom",0),
                n(s,"twinkle",0),n(s,"glitter",0),n(s,"spiral",0),n(s,"spin",0),n(s,"phase",0),n(s,"depth",1),(int)n(s,"tex",0));}
        double px(double t){double k=(1-Math.exp(-drag*t))/drag;return x+vx*k+spiral*Math.sin(spin*t+phase)*Math.min(1,t*3);}
        double py(double t){double k=(1-Math.exp(-drag*t))/drag;return y+vy*k+fall*(t-k)+spiral*Math.cos(spin*t+phase)*Math.min(1,t*3);}
    }
    private record LayoutKey(LyricTimeline.Line line, JsonObject type, double width, double height, double size, long seed) { }
    private final Map<LayoutKey,HanabiLayout.Placement> layouts=new LinkedHashMap<>();
    @Override public void reset(){layouts.clear();}
    private final Map<Integer,List<Spark>> plans=new HashMap<>();
    private final JsonArray profiles=data("hanabi.json").getAsJsonArray();
    private List<Spark> sparks(int index){return plans.computeIfAbsent(index,i->{var stars=new ArrayList<Spark>();
        for(JsonElement s:data("hanabi-bursts.json").getAsJsonArray().get(i).getAsJsonObject().getAsJsonArray("stars"))stars.add(Spark.read(s.getAsJsonObject()));return stars;});}
    @Override public void render(LyricCanvas g,LyricFrame f){
        double w=f.width(),h=f.contentHeight(),t=f.time();
        g.gradient(0,0,w,f.height(),0xff100b22,0xff452541);
        g.glow(w*.48,h*.9,w*.8,h*.8,0x3dce618e);
        for(int i=0;i<90;i++){
            double x=random(i*19+f.seed())*w,y=(random(i*23+7)*h+t*(3+random(i)*8))%h;
            g.circle(x,h-y,.6+random(i+4)*1.2,alpha(0xffffe6f5,.1+.2*(.5+.5*Math.sin(t*1.3+i))));
        }
        for(int idx=Math.max(0,f.index()-2);idx<=Math.min(f.index()+1,f.timeline().lines().size()-1);idx++){
            var line=f.timeline().line(idx);if(line==null)continue;
            int kind=variant(f.seed(),idx,profiles.size());JsonObject p=profiles.get(kind).getAsJsonObject();
            double age=t-line.start(),life=n(object(p,"burst"),"duration",2),r=clamp(age/Math.max(1,line.end()-line.start()));
            double bx=w*(.25+random(idx+f.seed())*.5),by=h*(s(p,"burstAt","").equals("above")?.3:.5);
            if(age>=0&&age<life+1){
                double camera=(t-line.start())*n(object(p,"camera"),"rise",.6)*17;
                drawBurst(g,kind,bx,by+camera,age-.18,f.energy().bass());
                if(b(p,"launch")&&age<.65){double py=h-(h-by)*out(age/.65);g.line(bx,h,bx,py,1,alpha(0xffffdfa1,1-age/.65));g.glow(bx,py,10,18,0xffffe5b0);}
            }
            double fade=smooth((age+.9)/.12)*(1-smooth((t-line.end()-.1)/.65));if(fade<=.001)continue;
            ornaments(g,f,p,age,fade,idx,false);
            drawType(g,f,line,p,age,fade,r,idx);
            ornaments(g,f,p,age,fade,idx,true);
        }
    }
    private void drawBurst(LyricCanvas g,int profile,double x,double y,double time,double bass){
        if(time<0)return;
        double flash=Math.exp(-time*12);g.glow(x,y,70,70,alpha(0xffffe5dc,flash*.65));
        double scale=1+bass*.08;
        for(Spark s:sparks(profile)){
            double t=time-s.start;if(t<0||t>=s.life)continue;
            double p=t/s.life,fade=clamp((1-p)/Math.max(.05,s.fade));
            double twinkle=1-s.twinkle*.55*(.5+.5*Math.sin(t*41+s.phase));
            if(s.strobe>0&&p>s.strobeFrom)twinkle*=.18+.82*Math.pow(.5+.5*Math.sin(t*s.strobe*2*Math.PI+s.phase),3);
            double a=fade*twinkle*(.45+.55*s.depth),px=x+s.px(t)*scale,py=y+s.py(t)*scale;
            int ink=color(s.color,s.endColor,clamp((p-s.shift)/.2));
            int segments=Math.min(10,s.segments);
            for(int j=segments;j>0;j--){double t0=Math.max(0,t-s.trail*j/Math.max(1,segments)),t1=Math.max(0,t-s.trail*(j-1)/Math.max(1,segments));
                g.line(x+s.px(t0)*scale,y+s.py(t0)*scale,x+s.px(t1)*scale,y+s.py(t1)*scale,
                        s.size*.45*(1-(double)j/(segments+1)),alpha(s.trailColor,a*(1-(double)j/(segments+1))*.6));}
            g.glow(px,py,s.size*3.8,s.size*3.8,alpha(ink,a*.25));
            if(s.tex==1)g.star(px,py,s.size*1.4,t*35,alpha(ink,a));else g.circle(px,py,s.size*.55,alpha(ink,a));
        }
    }
    private void drawType(LyricCanvas g,LyricFrame f,LyricTimeline.Line line,JsonObject p,double age,double fade,double progress,int index){
        JsonObject type=object(p,"text"),region=object(p,"region");
        double w=f.width(),h=f.contentHeight(),cx=n(region,"cx",.5)*w,cy=n(region,"cy",.5)*h;
        String layout=s(type,"layout","center"),block=s(type,"block","none");
        boolean vertical=layout.startsWith("vertical");
        int count=line.glyphs().size();
        double tilt=n(type,"tilt",0),exit=clamp((f.time()-line.end())/.65);
        double push=n(object(p,"camera"),"push",0),cam=1+push*progress,reserve=1+Math.abs(push);
        double maxWidth=Math.min(w*.9,w*n(region,"w",.74))/reserve;
        double maxHeight=Math.min(h*.64,h*n(region,"h",.5))/reserve;
        var key=new LayoutKey(line,type,maxWidth,maxHeight,Math.min(100,h*n(type,"heroSize",.1)),f.seed()+index*37);
        var placement=layouts.computeIfAbsent(key,k->HanabiLayout.place(k.line(),k.type(),k.size(),
                k.width(),k.height(),k.seed(),ch->g.width(ch,1,true)));
        if(layouts.size()>12)layouts.remove(layouts.keySet().iterator().next());
        double size=placement.size();
        cx=Math.max(w*.05+placement.width()*cam/2,Math.min(w*.95-placement.width()*cam/2,cx));
        cy=Math.max(h*.08+placement.height()*cam/2,Math.min(h*.76-placement.height()*cam/2,cy));
        g.push(cx,cy,cam,Math.toDegrees(tilt));
        if(block.equals("band")||block.equals("double")||block.equals("slant")){
            for(var band:placement.bands()){
                g.push(band.x(),band.y(),1,band.angle()+(block.equals("slant")?-6:0));
                g.rect(-band.length()/2-size*.15,-size*.7,band.length()+size*.3,size*1.4,alpha(0xfff8c1dd,fade*.9));
                if(block.equals("double"))g.rect(-band.length()/2,size*.8,band.length()+size*.3,size*.06,alpha(0xff8cd5ef,fade));
                g.pop();
            }
        }
        // Paint every backing first. A later letter's block must never cover earlier text.
        for(int layer=0;layer<2;layer++)for(var letter:placement.letters()){
            int i=letter.index();var glyph=line.glyphs().get(i);double advance=letter.width();
            double x=letter.x()-advance/2,y=letter.y()-size*.5;
            String entrance=s(type,"enter","rise"),sing=s(type,"sing","bounce");
            double rank=entrance.equals("zoom")||entrance.equals("burst")?Math.abs(i-(count-1)/2.0):i;
            if(entrance.equals("glitch")||entrance.equals("scramble"))rank=random(index*103+i*17)*Math.max(0,count-1);
            double duration=HanabiMotion.duration(entrance),step=n(type,"stagger",.045);
            step*=entrance.equals("burst")?.35:entrance.equals("zoom")?.6:entrance.equals("stamp")?1.35:1;
            double available=.9,ratio=Math.min(1,available/Math.max(.001,step*Math.max(0,count-1)+duration));
            step*=ratio;duration=Math.max(.1,duration*ratio);
            double enterTime=age+available-rank*step,seed=f.seed()+index*133+i*29;
            var pose=HanabiMotion.enter(entrance,enterTime/duration,enterTime,seed,-(x+advance/2)/size,-y/size,vertical);
            pose.add(HanabiMotion.exit(s(type,"exit","ascend"),exit,Math.max(0,f.time()-line.end()),seed,-(x+advance/2)/size,-y/size,vertical));
            pose.add(HanabiMotion.sing(sing,f.time()-glyph.start(),glyph.end()-glyph.start(),seed));
            int color=COLORS[Math.floorMod(i+index,COLORS.length)];double a=fade*pose.opacity;
            if(a<=.001)continue;
            g.push(x+advance/2+pose.x*size,y+size*.5+pose.y*size,1,letter.rotation()+Math.toDegrees(pose.rotation));
            GL11.glScaled(Math.abs(pose.sx)<.001?.001:pose.sx,Math.max(.001,pose.sy),1);
            if(layer==0){
                if(block.equals("tile")||block.equals("stair"))g.rect(-advance/2-size*.07,-size*.64,advance+size*.14,size*1.14,alpha(color,a*.88));
                if(block.equals("pill"))g.roundedRect(-advance/2-size*.13,-size*.64,advance+size*.26,size*1.2,size*.3,alpha(color,a*g.opacity));
                if(block.equals("ticket")){
                    g.rect(-advance/2-size*.12,-size*.64,advance+size*.24,size*1.2,alpha(color,a));
                    for(int j=0;j<5;j++)g.circle(advance/2+size*.12,-size*.52+j*size*.23,2,alpha(0xff25172c,a));
                }
                if(block.equals("split")){g.rect(-advance/2-size*.09,-size*.64,advance+size*.18,size*.57,alpha(color,a));g.rect(-advance/2-size*.09,-size*.03,advance+size*.18,size*.57,alpha(COLORS[(i+index+1)%COLORS.length],a));}
                if(block.equals("marker"))g.rect(-advance/2-size*.06,size*.05,advance+size*.12,size*.45,alpha(color,a*.75));
                if(block.equals("circle"))g.circle(0,0,size*.7,alpha(color,a*.85));
                if(block.equals("outline"))g.frame(-advance/2-size*.09,-size*.64,advance+size*.18,size*1.2,2,alpha(color,a));
                g.pop();continue;
            }
            int ink=Set.of("none","outline","marker").contains(block)?0xfffff4fb:0xff30213e;
            ink=LyricMath.color(LyricMath.color(ink,color,pose.tint),0xfffff7e4,pose.flash);
            String text=pose.scramble<0?glyph.text():String.valueOf("花火光星☆+×0123456789".charAt(pose.scramble%16));
            if(b(type,"hardShadow"))g.text(text,-advance/2+3,-size*.5+4,size,alpha(0xff100d25,a*.6),true);
            if(pose.blur>.01)for(int j=0;j<5;j++)g.text(text,-advance/2+(j-2)*size*pose.blur*.05,-size*.5,size,alpha(ink,a*.045),true);
            if(pose.rgb>0){g.text(text,-advance/2-size*pose.rgb,-size*.5,size,alpha(0xffff4368,a*.5),true);g.text(text,-advance/2+size*pose.rgb,-size*.5,size,alpha(0xff55e7ff,a*.5),true);}
            if(pose.split>.002){
                slice(g,text,-advance/2-size*pose.split,-size*.5,size,alpha(ink,a),-advance/2-size*pose.split,-size*pose.split);
                slice(g,text,-advance/2+size*pose.split,-size*.5,size,alpha(ink,a),size*pose.split,advance/2+size*pose.split);
            }else if(pose.reveal<.999)slice(g,text,-advance/2,-size*.5,size,alpha(ink,a),-advance/2,advance*(pose.reveal-.5));
            else g.glowText(text,-advance/2,-size*.5,size,alpha(ink,a),pose.glow*size*.22,true);
            if(sing.equals("underline"))g.rect(-advance/2,size*.48,advance*glyph.progress(f.time()),3,alpha(color,a));
            g.pop();
        }
        g.pop();
    }

    private static void slice(LyricCanvas g,String text,double x,double y,double size,int ink,double left,double right){
        var equation=BufferUtils.createDoubleBuffer(4);
        GL11.glPushAttrib(GL11.GL_TRANSFORM_BIT);
        try{
            equation.put(new double[]{1,0,0,-left}).flip();GL11.glClipPlane(GL11.GL_CLIP_PLANE0,equation);
            equation.clear();equation.put(new double[]{-1,0,0,right}).flip();GL11.glClipPlane(GL11.GL_CLIP_PLANE1,equation);
            GL11.glEnable(GL11.GL_CLIP_PLANE0);GL11.glEnable(GL11.GL_CLIP_PLANE1);
            g.text(text,x,y,size,ink,true);
        }finally{GL11.glPopAttrib();}
    }
    private void ornaments(LyricCanvas g,LyricFrame f,JsonObject profile,double age,double fade,int index,boolean front){
        JsonObject region=object(profile,"region");
        double cx=f.width()*n(region,"cx",.5),cy=f.contentHeight()*n(region,"cy",.5);
        for(JsonElement entry:array(profile,"ornaments")){
            JsonObject o=entry.getAsJsonObject();if(s(o,"layer","back").equals("front")!=front)continue;
            double local=age+.9-n(o,"delay",0),enter=out(local/.6);if(enter<=0)continue;
            double size=f.contentHeight()*.11*n(o,"scale",1),radius=size*(1.5+enter*.7),a=fade*enter*.65;
            int ink=alpha(COLORS[Math.floorMod(index+2,COLORS.length)],a);
            int count=Math.max(3,(int)(18*n(o,"density",.5)));
            String kind=s(o,"kind","ring");
            g.push(cx,cy,1,0);
            switch(kind){
                case "ring","dash-circle","spokes","rays","orbit" -> {
                    if(kind.equals("ring"))g.ring(0,-size*.3,radius,2,0,Math.PI*2*enter,ink);
                    else for(int j=0;j<count;j++){
                        double theta=j*Math.PI*2/count+local*.12,cs=Math.cos(theta),sn=Math.sin(theta);
                        if(kind.equals("dash-circle"))g.ring(0,0,radius,2,theta,Math.PI/count,ink);
                        else if(kind.equals("orbit"))g.circle(cs*radius,sn*radius,size*.035,ink);
                        else g.line(cs*radius,sn*radius,cs*(radius+size*.7),sn*(radius+size*.7),kind.equals("rays")?2:1,ink);
                    }
                }
                case "brackets","stamp","squares" -> {
                    double w=f.width()*n(region,"w",.74)*.55,h=size*.9;
                    if(kind.equals("stamp"))g.frame(-w,-h,w*2,h*2,3,ink);
                    else for(int j=0;j<4;j++){double x=j%2==0?-w:w,y=j<2?-h:h,dx=j%2==0?size*.3:-size*.3,dy=j<2?size*.3:-size*.3;
                        g.line(x,y,x+dx,y,2,ink);g.line(x,y,x,y+dy,2,ink);if(kind.equals("squares"))g.frame(x-5,y-5,10,10,1,ink);}
                }
                case "underline","dots-line","zigzag","wave" -> {
                    double w=f.width()*n(region,"w",.74),lastX=-w/2,lastY=size*.9;
                    for(int j=1;j<=60;j++){double x=-w/2+w*j/60,y=size*.9+(kind.equals("wave")?Math.sin(j*.5+local*2)*size*.08:kind.equals("zigzag")?(j%4<2?-1:1)*size*.06:0);
                        if(kind.equals("dots-line")){if(j%3==0)g.circle(x,y,1.6,ink);}else if(j/60.0<enter)g.line(lastX,lastY,x,y,2,ink);lastX=x;lastY=y;}
                }
                default -> {
                    for(int j=0;j<count;j++){
                        double seed=f.seed()+index*53+j*37,theta=random(seed)*Math.PI*2;
                        double spread=s(o,"placement","").equals("screen")?f.width()*.5:radius;
                        double x=Math.cos(theta)*spread*(.5+random(seed+1)*.7),y=Math.sin(theta)*size*(1.4+random(seed+2)*2.5);
                        x+=Math.cos(theta)*local*size*.12;y+=(kind.equals("confetti")?1:-1)*local*size*.15;
                        switch(kind){
                            case "sparkle","glints","cross" -> g.star(x,y,size*(kind.equals("cross")?.12:.08),local*25,ink);
                            case "halftone" -> {for(int k=0;k<4;k++)g.circle(x+k*5,y,1.2,ink);}
                            case "petals" -> {g.push(x,y,1,local*30+j*19);g.glow(0,0,size*.05,size*.13,ink);g.pop();}
                            case "shards" -> g.polygon(ink,x,y,x+size*.1,y-size*.2,x+size*.14,y+size*.06);
                            case "streaks" -> g.line(x,y,x+Math.cos(theta)*size*.7,y+Math.sin(theta)*size*.7,1,ink);
                            case "chevrons" -> {g.line(x-7,y-7,x,y,2,ink);g.line(x,y,x-7,y+7,2,ink);}
                            default -> {g.push(x,y,1,local*45+j*13);g.rect(-2,-5,4,10,ink);g.pop();}
                        }
                    }
                }
            }
            g.pop();
        }
    }
}
