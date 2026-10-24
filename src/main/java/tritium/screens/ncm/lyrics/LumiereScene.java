// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import com.google.gson.*;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import tritium.desktop.background.BackgroundShader;
import java.io.IOException;
import java.util.*;
import static tritium.screens.ncm.lyrics.FoliaData.*;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Original volumetric light-field GLSL, beam rigs and procedural line drawings on LWJGL. */
final class LumiereScene implements LyricScene {
    private BackgroundShader shader;
    private final LumiereWindow window = new LumiereWindow();
    private final Map<String,Integer> uniforms=new HashMap<>();
    private final List<double[]> beams=new ArrayList<>();
    private int uniform(String name){return uniforms.computeIfAbsent(name,shader::uniform);}
    private void v(String name,double a,double b,double c,double d){GL20.glUniform4f(uniform(name),(float)a,(float)b,(float)c,(float)d);}
    private void rgb(String name,double r,double g,double b){GL20.glUniform3f(uniform(name),(float)r,(float)g,(float)b);}
    private static double osc(JsonObject b,double time){return n(b,"amplitude",0)*Math.sin((time/Math.max(.001,n(b,"period",1))+n(b,"phase",0))*Math.PI*2);}
    private static int pattern(String s){return switch(s){case "blinds","rings"->1;case "cross","slits"->2;case "lattice","sources"->3;case "leaves","airy"->4;default->0;};}
    @Override public void render(LyricCanvas g,LyricFrame f){
        if(shader==null)try{shader=new BackgroundShader("#version 120\nvarying vec2 vPos;void main(){vPos=gl_Vertex.xy;gl_Position=ftransform();}",resource("lumiere.frag"));}
        catch(IOException e){throw new IllegalStateException("Cannot compile native Lumiere light field",e);}
        g.rect(0,0,f.width(),f.height(),0xff080b12);
        JsonObject profile=profile("lumiere.json",f),rig=object(profile,"light");
        double age=f.current()==null?f.time():f.time()-f.current().start();
        double drive=.65+.35*smooth(age/.8);
        JsonObject camera=object(profile,"camera");
        double progress=f.current()==null?0:(1-Math.cos(f.current().progress(f.time())*Math.PI))/2;
        double driftX=n(camera,"driftX",0)*progress+(Math.sin(f.time()*.21+.4)*.6+Math.sin(f.time()*.53+1.9)*.4)*.006;
        double driftY=n(camera,"driftY",0)*progress+(Math.cos(f.time()*.17+1.1)*.6+Math.sin(f.time()*.47+.3)*.4)*.006;
        g.push(f.width()/2+driftX*f.width(),f.height()/2+driftY*f.height(),
                (1+n(camera,"push",0)*progress)*(1+.008*Math.sin(f.time()*.31+.7)),Math.toDegrees(.004*Math.sin(f.time()*.13+2.1)));
        g.push(-f.width()/2,-f.height()/2,1,0);
        g.api.getGLStateManager().disableAlpha();g.api.getGLStateManager().enableBlend();
        g.api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
        shader.draw(f.width(),f.height(),(int)f.width(),(int)f.height(),(float)f.time(),()->rig(rig,profile,f,age,drive,g.opacity));
        g.api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
        lineArt(g,f,object(profile,"lineArt"),age);
        motes(g,f,object(profile,"motes"),false);
        window.render(g,f,this::light);
        motes(g,f,object(profile,"front"),true);
        g.pop();g.pop();
    }
    private void rig(JsonObject rig,JsonObject profile,LyricFrame f,double local,double drive,double opacity){
        double aspect=f.width()/f.height(),t=f.time();JsonArray specs=array(rig,"beams");beams.clear();
        for(int i=0;i<Math.min(6,specs.size());i++){
            JsonObject b=specs.get(i).getAsJsonObject();double angle=n(b,"angle",0)+osc(object(b,"sway"),t);
            double length=n(b,"length",1)*(b.has("reveal")?.06+.94*smooth(local/n(b,"reveal",1)):1);
            double intensity=n(b,"intensity",1)*(1+osc(object(b,"pulse"),t))*drive*(1+.15*Math.pow(f.energy().bass(),1.5));
            double ox=n(b,"x",.5)*aspect,oy=n(b,"y",0),dx=Math.cos(angle),dy=Math.sin(angle),half=n(b,"width",0)/2,spread=Math.tan(n(b,"spread",.1));
            v("uBeamA["+i+"]",ox,oy,dx,dy);v("uBeamB["+i+"]",half,spread,n(b,"softness",.6),length);
            v("uBeamC["+i+"]",intensity,n(b,"streaks",0),n(b,"streakFreq",7),t*n(b,"streakSpeed",.08));
            JsonArray tint=array(b,"tint");double r=tint.size()>0?tint.get(0).getAsDouble():1,gg=tint.size()>1?tint.get(1).getAsDouble():.85,bb=tint.size()>2?tint.get(2).getAsDouble():.62;
            v("uBeamD["+i+"]",r,gg,bb,n(b,"core",.5));JsonObject gobo=object(b,"gobo");
            v("uBeamE["+i+"]",pattern(s(gobo,"pattern","")),n(gobo,"frequency",0),n(gobo,"duty",1),t*n(gobo,"drift",0));
            v("uBeamF["+i+"]",n(b,"spectrum",0),n(b,"reach",0),0,0);
            beams.add(new double[]{ox,oy,dx,dy,half,spread,n(b,"softness",.6),length,intensity});
        }
        v("uFrame",f.width(),f.height(),t,Math.min(6,specs.size()));GL20.glUniform1f(uniform("vAlpha"),(float)opacity);
        JsonObject fog=object(rig,"fog");v("uFogA",n(fog,"density",1),n(fog,"tyndallBase",.12),n(fog,"scale",2.4),n(fog,"warp",.9));
        v("uFogB",n(fog,"driftX",.008),n(fog,"driftY",-.04),n(fog,"ambient",.03),5);
        rgb("uFogColor",.9,.8,.65);rgb("uGlareColor",1,.88,.69);v("uDark",.008,.012,.024,.7);
        JsonObject glare=object(rig,"glare");v("uGlare",n(glare,"x",0)*aspect,n(glare,"y",0),n(glare,"radius",.1),n(glare,"intensity",0)*drive);v("uGlareB",n(glare,"streak",0),0,0,0);
        JsonObject caustic=object(rig,"caustic"),floor=object(caustic,"floor");
        v("uCaustic",n(caustic,"scale",1),n(caustic,"speed",1),n(caustic,"inBeam",0),caustic.entrySet().isEmpty()?0:1);
        v("uCausticFloor",n(floor,"cx",.5)*aspect,n(floor,"cy",.7),n(floor,"rx",.3)*aspect,n(floor,"ry",.2));v("uCausticB",n(floor,"strength",0),0,0,0);
        JsonObject wave=object(rig,"wave"),region=object(profile,"region");
        v("uWave",pattern(s(wave,"mode","")),n(wave,"frequency",1),n(wave,"speed",1),n(wave,"strength",0));
        v("uWaveB",n(wave,"cx",.5)*aspect,n(wave,"cy",.5),n(wave,"radius",.4),n(wave,"separation",.2));
        v("uShield",n(region,"cx",.5)*aspect,n(region,"cy",.5),n(region,"w",.7)*aspect/2,n(region,"h",.5)/2);v("uShieldB",n(wave,"shield",.45),0,0,0);
    }
    private double light(double x,double y){
        double sum=0;
        for(double[] b:beams){double px=x-b[0],py=y-b[1],along=px*b[2]+py*b[3];if(along<=0)continue;
            double q=Math.abs(-px*b[3]+py*b[2])/(Math.abs(b[4]+along*b[5])+.0001);
            sum+=smooth((1-q)/Math.max(.02,b[6]))*Math.exp(-along/Math.max(.001,b[7]))*b[8];}
        return 1-Math.exp(-sum);
    }
    private void lineArt(LyricCanvas g,LyricFrame f,JsonObject art,double local){
        double sx=f.width()/(16.0/9),sy=f.height();
        for(JsonElement item:array(art,"paths")){
            JsonObject p=item.getAsJsonObject();JsonArray points=array(p,"points");
            double progress=out((local-n(p,"delay",0))/Math.max(.1,n(p,"span",1))),count=progress*(points.size()-1);
            int ink=alpha(0xffe3cc9e,n(p,"alpha",.4)*(.5+.5*smooth(local/.8)));
            for(int i=1;i<points.size()&&i<=count+1;i++){
                JsonArray a=points.get(i-1).getAsJsonArray(),b=points.get(i).getAsJsonArray();double fraction=clamp(count-i+1);
                double x=a.get(0).getAsDouble()*sx,y=a.get(1).getAsDouble()*sy;
                g.line(x,y,mix(x,b.get(0).getAsDouble()*sx,fraction),mix(y,b.get(1).getAsDouble()*sy,fraction),Math.max(.55,n(p,"width",.001)*sy),ink);
            }
        }
        for(JsonElement item:array(art,"nodes")){
            JsonObject n=item.getAsJsonObject();JsonArray at=array(n,"at");if(at.size()<2)continue;
            double x=at.get(0).getAsDouble()*sx,y=at.get(1).getAsDouble()*sy,a=smooth((local-n(n,"delay",0))/.8)*(.4+.6*Math.pow(.5+.5*Math.sin(f.time()*1.5+n(n,"twinklePhase",0)),2));
            double size=n(n,"size",.004)*sy;g.glow(x,y,size*4,size*4,alpha(0xffe4cb99,a*.3));g.star(x,y,size,0,alpha(0xffffebbd,a*.7));
        }
    }
    private void motes(LyricCanvas g,LyricFrame f,JsonObject p,boolean front){
        int count=(int)n(p,"count",front?9:260);double t=f.time();
        for(int i=0;i<count;i++){
            double seed=f.seed()+i*79+(front?10000:0),phase=random(seed+2)*Math.PI*2;
            double x=(random(seed)+n(p,"driftX",.004)*t+Math.sin(t/n(p,"swirlPeriod",9)+phase)*n(p,"swirl",.02))%1;
            double y=(random(seed+1)+n(p,"driftY",-.01)*t+Math.cos(t/n(p,"swirlPeriod",9)+phase)*n(p,"swirl",.02))%1;
            x=(x+1)%1*f.width();y=(y+1)%1*f.height();
            double a=n(p,"ambient",.015)+light(x/f.height(),y/f.height())*n(p,"gain",1.3);
            a*=1-n(p,"twinkle",.5)*(.5+.5*Math.sin(t*2+phase));
            double size=mix(n(p,"sizeMin",.004),n(p,"sizeMax",.013),random(seed+3))*f.height();
            g.glow(x,y,size,size,alpha(0xffffe4ae,Math.min(front?.12:.48,a)));
            if(!front)g.circle(x,y,Math.max(.5,size*.13),alpha(0xffffeccd,Math.min(.8,a)));
        }
    }
    @Override public void close(){if(shader!=null)shader.close();shader=null;uniforms.clear();}
}
