// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import com.google.gson.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.util.glu.GLU;
import tritium.desktop.background.BackgroundShader;
import tritium.rendering.rendersystem.RenderSystem;
import java.io.IOException;
import java.util.*;
import static tritium.screens.ncm.lyrics.FoliaData.*;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Native perspective corridor, thirteen dolly shots, original welded point lattices and ripple shaders. */
final class DioramaScene implements LyricScene {
    private static final String[] SHOTS={"pushIn","pullBack","orbit","track","crane","hold","swell","spiral","pendulum","flyby","arc","float","glide"};
    private record Vec(double x,double y,double z){
        Vec add(Vec v){return new Vec(x+v.x,y+v.y,z+v.z);}Vec mul(double k){return new Vec(x*k,y*k,z*k);}
        Vec unit(){return mul(1/Math.max(.0001,Math.sqrt(x*x+y*y+z*z)));}
        Vec cross(Vec v){return new Vec(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x);}
    }
    private record Frame(Vec position,Vec forward,Vec right,Vec up){Vec at(double r,double u,double d){return position.add(right.mul(r)).add(up.mul(u)).add(forward.mul(d));}}
    private record Placement(double r,double u,double scale,double roll,double yaw,double look) { }
    private record Surface(int list,double radius,double spacing) { }
    private record Shape(Surface surface,Vec anchor,double scale,double stretch,int color,double phase,boolean far) { }
    private final List<Frame> frames=new ArrayList<>();
    private final Map<String,Surface> surfaces=new HashMap<>();
    private final Map<Integer,List<Shape>> shapes=new HashMap<>();
    private final Map<String,Integer> uniforms=new HashMap<>();
    private final Spring[] follow={new Spring(64,16),new Spring(64,16),new Spring(64,16),new Spring(80,18),new Spring(80,18),new Spring(80,18)};
    private final double[] births=new double[9],strengths=new double[9],lastLevels=new double[3];
    private final int[] slots=new int[3];
    private LyricTimeline timeline;
    private BackgroundShader shader;
    private double seed;
    private double textDepth=5.2;
    private static double seeded(double seed){double x=Math.sin(seed*12.9898)*43758.5453;return x-Math.floor(x);}
    private Placement placement(int i){
        double r=(Math.sin(i*.47+seed*.019)*.7+(seeded(seed+i*11+2)-.5)*.6)*1.8;
        double u=(Math.sin(i*.29+seed*.027+.9)*.7+(seeded(seed+i*11+4)-.5)*.6)*1.2;
        return new Placement(r,u,.82+seeded(seed+i*11+5)*.46,(seeded(seed+i*11+6)-.5)*.4,
                (seeded(seed+i*11+7)-.5)*.32,(seeded(seed+i*11+8)-.5)*2.2);
    }
    private void prepare(LyricFrame f){
        if(timeline==f.timeline())return;timeline=f.timeline();frames.clear();shapes.clear();seed=Math.floorMod(f.seed(),100000);
        Vec pos=new Vec(0,0,0);
        for(int i=0;i<Math.max(1,timeline.lines().size());i++){
            double yaw=.5*(Math.sin(i*.23+seed*.017)*.62+Math.sin(i*.11+seed*.041+1.3)*.38),pitch=.32*Math.sin(i*.17+seed*.029+.7);
            Vec forward=new Vec(Math.sin(yaw)*Math.cos(pitch),Math.sin(pitch),-Math.cos(yaw)*Math.cos(pitch));
            Vec right=forward.cross(new Vec(0,1,0)).unit(),up=right.cross(forward).unit();frames.add(new Frame(pos,forward,right,up));pos=pos.add(forward.mul(8));
        }
        Arrays.fill(births,-100);Arrays.fill(strengths,0);Arrays.fill(lastLevels,0);
    }
    private int shot(int index){return FoliaData.variant((long)seed,index,SHOTS.length);}
    private Vec offset(int shot,int index,double progress,double words){
        double e=smooth(progress),dir=seeded(seed+index*7+3)<.5?-1:1,r=0,u=.5,back=5.2,theta;
        switch(shot){
            case 0->{back=5.2*(1.5-.8*e);u=.5*(1.2-.5*e)+.15;}
            case 1->{back=5.2*(.85+1.15*e);u=.5+1.7*e;}
            case 2->{theta=dir*.55*(2*e-1);r=5.2*Math.sin(theta);back=5.2*Math.cos(theta);u=.9;}
            case 3->{r=dir*5.2*.34*Math.sin(Math.PI*words);back=5.2*1.06;u=.62;}
            case 4->{u=mix(dir>0?-1.5:2.4,dir>0?2.4:-1.4,e);back=5.2*1.05;}
            case 6->{theta=dir*.4*e;r=5.2*Math.sin(theta)*1.1;back=5.2*(1+.35*e);u=.8+1.2*e;}
            case 7->{theta=dir*.7*(2*e-1);r=5.2*Math.sin(theta)*.85;back=5.2*(1.05-.1*e);u=-.3+2.4*e;}
            case 8->{r=dir*5.2*.45*(2*e-1);u=1.4-.9*Math.sin(Math.PI*e);back=5.2*1.02;}
            case 9->{r=dir*5.2*(.7-1.4*e);back=5.2*(.78+.12*Math.sin(Math.PI*e));u=.55;}
            case 10->{theta=dir*.5*Math.sin(Math.PI*e);r=5.2*Math.sin(theta)*1.3;back=5.2*(1.05+.12*(1-Math.cos(theta)));u=.5+.5*Math.sin(Math.PI*e);}
            case 11->{r=dir*5.2*.16*Math.sin(e*Math.PI*2);u=1.1+.5*Math.sin(e*Math.PI*2+1);back=5.2*(1.05+.06*Math.sin(e*Math.PI));}
            case 12->{r=dir*5.2*.22*e;u=.5+1.1*e;back=5.2*(1.25-.3*e);}
            default->{ }
        }
        return new Vec(r,u,back);
    }
    @Override public void render(LyricCanvas g,LyricFrame f){
        prepare(f);SceneBackdrop.ambient(g,f,0xff090e18);
        if(shader==null)try{String fragment=resource("diorama.frag").replace("uniform float uGlow;","uniform float uGlow;\nuniform float uOpacity;").replace("alpha);","alpha * uOpacity);");
            shader=new BackgroundShader(resource("diorama.vert"),fragment);
        }catch(IOException e){throw new IllegalStateException("Cannot compile native Diorama particles",e);}
        int current=Math.max(0,f.index());Frame frame=frames.get(Math.min(current,frames.size()-1));Placement placement=placement(current);
        var line=f.current();double progress=line==null?0:line.progress(f.time()),wordProgress=line==null?0:line.characterProgress(f.time())/Math.max(1,line.glyphs().size());
        Vec offset=offset(shot(current),current,progress,wordProgress);
        double settle=line==null?1:1-smooth((f.time()-line.end()-3)/5);
        // Keep the tracked lyric inside a narrow viewport while retaining the shot's depth motion.
        double cameraWidth=clamp((f.width()/f.height()-.65)/.95);
        textDepth=Math.max(2,mix(5.2,offset.z,settle));
        Vec target=frame.at(placement.r+(placement.look+(wordProgress-.5)*.7)*settle*cameraWidth,placement.u,0);
        Vec camera=target.add(frame.right.mul((offset.x*settle+Math.sin(f.time()*.23)*.08)*cameraWidth)).add(frame.up.mul(offset.y)).add(frame.forward.mul(-mix(5.2,offset.z,settle)));
        double[] desired={camera.x,camera.y,camera.z,target.x,target.y,target.z},value=new double[6];
        for(int i=0;i<6;i++){if(f.discontinuity())follow[i].snap(desired[i]);value[i]=follow[i].step(desired[i],f.dt());}
        updateRipples(f);
        int matrixMode=GL11.glGetInteger(GL11.GL_MATRIX_MODE);GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPushMatrix();GL11.glLoadIdentity();
        GLU.gluPerspective(50,(float)(f.width()/f.height()),.1f,200);GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPushMatrix();GL11.glLoadIdentity();
        GLU.gluLookAt((float)value[0],(float)value[1],(float)value[2],(float)value[3],(float)value[4],(float)value[5],(float)frame.up.x,(float)frame.up.y,(float)frame.up.z);
        try{
            var visible=new ArrayList<Shape>();for(int i=Math.max(0,current-2);i<Math.min(frames.size(),current+4);i++)visible.addAll(shapes.computeIfAbsent(i,this::formation));
            if(shapes.size()>12)shapes.keySet().removeIf(i->i<current-2||i>current+3);
            visible.sort(Comparator.comparingDouble((Shape s)->distance(s.anchor,value)).reversed());
            g.api.getGLStateManager().disableAlpha();g.api.getGLStateManager().enableBlend();g.api.getGLStateManager().disableDepth();g.api.getGLStateManager().disableTexture2D();
            GL11.glEnable(GL20.GL_VERTEX_PROGRAM_POINT_SIZE);GL11.glEnable(GL20.GL_POINT_SPRITE);
            shader.withProgram(()->{
                particleUniforms(f,value,g.opacity);
                for(int pass=0;pass<2;pass++){
                    one("uGlowPass",pass);g.api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA,pass==0?GL11.GL_ONE_MINUS_SRC_ALPHA:GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
                    for(Shape s:visible){vec("aAnchor",s.anchor.x,s.anchor.y,s.anchor.z);vec("aScale",s.scale,s.stretch,s.surface.radius);vec("aStyle",0,s.color,s.far?1:0);
                        one("aPhase",s.phase);one("uWaveNumberMax",Math.min(9,Math.PI/(2*Math.max(.01,s.surface.spacing))));GL11.glCallList(s.surface.list);}
                }
            });
            GL11.glDisable(GL20.GL_POINT_SPRITE);GL11.glDisable(GL20.GL_VERTEX_PROGRAM_POINT_SIZE);g.api.getGLStateManager().enableTexture2D();
            for(int i=Math.min(frames.size()-1,current+3);i>=Math.max(0,current-2);i--)drawText(g,f,i,current);
        }finally{
            GL11.glDisable(GL20.GL_POINT_SPRITE);GL11.glDisable(GL20.GL_VERTEX_PROGRAM_POINT_SIZE);
            g.api.getGLStateManager().enableTexture2D();g.api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glPopMatrix();GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPopMatrix();GL11.glMatrixMode(matrixMode);
        }
    }
    private static double distance(Vec p,double[] c){return Math.pow(p.x-c[0],2)+Math.pow(p.y-c[1],2)+Math.pow(p.z-c[2],2);}
    private Surface surface(String kind,double stretch){
        String key=kind+":"+Math.max(1,Math.min(3,(int)Math.round(stretch)));
        return surfaces.computeIfAbsent(key,k->{JsonObject data=data("diorama-surfaces.json").getAsJsonObject().getAsJsonObject(k);JsonArray p=array(data,"positions"),n=array(data,"normals");
            int list=GL11.glGenLists(1);GL11.glNewList(list,GL11.GL_COMPILE);GL11.glBegin(GL11.GL_POINTS);
            for(int i=0;i<p.size();i+=3){GL11.glNormal3d(n.get(i).getAsDouble(),n.get(i+1).getAsDouble(),n.get(i+2).getAsDouble());GL11.glTexCoord2d(i*.01,random(i));GL11.glVertex3d(p.get(i).getAsDouble(),p.get(i+1).getAsDouble(),p.get(i+2).getAsDouble());}
            GL11.glEnd();GL11.glEndList();return new Surface(list,n(data,"radius",1),n(data,"spacing",.2));});
    }
    private List<Shape> formation(int index){
        JsonArray list=data("diorama-formations.json").getAsJsonObject().getAsJsonArray(SHOTS[shot(index)]).get(index%12).getAsJsonArray();
        var result=new ArrayList<Shape>();Frame frame=frames.get(index);Placement p=placement(index);
        for(JsonElement el:list){JsonObject s=el.getAsJsonObject(),pos=object(s,"position");double stretch=n(s,"stretchY",1);
            result.add(new Shape(surface(s(s,"kind","box"),stretch),frame.at(p.r+n(pos,"x",0),p.u+n(pos,"y",0),-n(pos,"z",0)),n(s,"scale",1),stretch,(int)n(s,"colorSlot",0),random(index+result.size()*13)*Math.PI*2,s(s,"layer","").equals("far")));}
        return result;
    }
    private void drawText(LyricCanvas g,LyricFrame f,int index,int current){
        var line=timeline.line(index);if(line==null)return;Frame frame=frames.get(index);Placement p=placement(index);Vec position=frame.at(p.r,p.u,0);
        double neighbor=switch(index-current){case -2->.1;case -1->.3;case 0->.92;case 1->.34;case 2->.16;case 3->.06;default->0;};
        double worldSize=.62*p.scale,logicalSize=52;
        double worldWidth=textDepth*Math.tan(Math.toRadians(25))*(f.width()/f.height())*2*.64;
        double availableWidth=worldWidth*logicalSize/worldSize;
        var rows=g.wrapGlyphs(line,logicalSize,availableWidth,true);
        double fit=Math.min(1,3.0/Math.max(1,rows.size()));
        if(fit<1)rows=g.wrapGlyphs(line,logicalSize,availableWidth/fit,true);
        var matrix=BufferUtils.createFloatBuffer(16);matrix.put(new float[]{(float)frame.right.x,(float)frame.right.y,(float)frame.right.z,0,(float)frame.up.x,(float)frame.up.y,(float)frame.up.z,0,(float)-frame.forward.x,(float)-frame.forward.y,(float)-frame.forward.z,0,(float)position.x,(float)position.y,(float)position.z,1}).flip();
        GL11.glPushMatrix();GL11.glMultMatrix(matrix);GL11.glRotated(Math.toDegrees(p.roll),0,0,1);GL11.glRotated(Math.toDegrees(p.yaw),0,1,0);GL11.glScaled(worldSize/logicalSize*fit,-worldSize/logicalSize*fit,1);
        double y=-(rows.size()-1)*logicalSize*.65;
        for(var row:rows){
            double width=0;for(var glyph:row)width+=g.width(glyph.text(),logicalSize,true);
            double x=-width/2;
            for(var glyph:row){
                double sung=glyph.progress(f.time()),active=Math.sin(sung*Math.PI),age=f.time()-glyph.end();
                int color=color(0xffdfe8fa,f.accent(),active);
                double opacity=neighbor*(f.time()<glyph.start()?.5:1);
                if(index==current&&age>0&&age<1.4){double detached=smooth(age/.5),fade=(1-age/1.4)*.6;
                    g.glowText(glyph.text(),x,y-logicalSize*.55-detached*logicalSize*.5,logicalSize*(1+detached*.3),alpha(f.accent(),fade),8,true);}
                g.glowText(glyph.text(),x,y-logicalSize*.55,logicalSize,alpha(color,opacity),index==current?active*14:0,true);x+=g.width(glyph.text(),logicalSize,true);
            }
            y+=logicalSize*1.3;
        }
        GL11.glPopMatrix();
    }
    private int uniform(String key){return uniforms.computeIfAbsent(key,shader::uniform);}
    private void one(String key,double value){GL20.glUniform1f(uniform(key),(float)value);}
    private void vec(String key,double x,double y,double z){GL20.glUniform3f(uniform(key),(float)x,(float)y,(float)z);}
    private void updateRipples(LyricFrame f){
        if(f.discontinuity()){Arrays.fill(births,-100);Arrays.fill(strengths,0);Arrays.fill(lastLevels,0);}
        double[] levels={f.energy().bass(),f.energy().mid(),f.energy().treble()};
        for(int b=0;b<3;b++){
            if(levels[b]>.08&&levels[b]>lastLevels[b]*1.18+.025){int slot=b*3+slots[b]++%3;births[slot]=f.time();strengths[slot]=Math.min(1,levels[b]*1.5);}
            lastLevels[b]=mix(lastLevels[b],levels[b],1-Math.exp(-f.dt()*8));
        }
    }
    private void particleUniforms(LyricFrame f,double[] camera,double opacity){
        vec("cameraPosition",camera[0],camera[1],camera[2]);one("uTime",f.time());one("uCorridor",0);one("uAmplitude",.75);one("uMaxSwell",.8);
        one("uDetail",f.energy().treble());one("uOffsetGain",f.energy().mid());one("uFlow",1);one("uFormation",1);one("uScatter",3);one("uPulse",1+f.energy().bass()*.03);
        one("uSpectralCentroid",f.energy().treble());one("uViewportHeight",RenderSystem.getFramebufferHeight());one("uSizeBase",.025);one("uSizeGain",.1);one("uGlow",.65);one("uOpacity",opacity);
        vec("uPrimaryColor",.72,.8,.95);vec("uAccentColor",Math.pow((f.accent()>>16&255)/255.0,2.2),Math.pow((f.accent()>>8&255)/255.0,2.2),Math.pow((f.accent()&255)/255.0,2.2));vec("uSecondaryColor",.45,.32,.65);
        double[][] bands={{1.45,.9,.66,3.4},{.9,1.6,.36,5.5},{.5,2.5,.22,9}};
        for(int i=0;i<9;i++){double a=random(i+seed)*Math.PI*2;GL20.glUniform4f(uniform("uRippleSource["+i+"]"),(float)Math.cos(a),(float)Math.sin(a),0,(float)births[i]);
            double[] b=bands[i/3];GL20.glUniform4f(uniform("uRippleShape["+i+"]"),(float)(b[0]*strengths[i]),(float)b[1],(float)b[2],(float)b[3]);}
    }
    @Override public void reset(){timeline=null;shapes.clear();}
    @Override public void close(){if(shader!=null)shader.close();shader=null;for(Surface s:surfaces.values())GL11.glDeleteLists(s.list,1);surfaces.clear();uniforms.clear();shapes.clear();}
}
