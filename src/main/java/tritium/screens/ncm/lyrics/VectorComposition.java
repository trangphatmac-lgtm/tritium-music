// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

import com.google.gson.*;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;
import org.lwjgl.util.glu.GLUtessellatorCallbackAdapter;
import java.util.ArrayList;
import java.util.List;
import static tritium.screens.ncm.lyrics.FoliaData.*;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** Compiles Folia's vectors once. GLU tessellation preserves concave silhouettes and cutout holes. */
final class VectorComposition {
    record Path(double[] xy, boolean closed) { }
    record Paint(int color, double alpha, double width, List<Path> paths, List<Path> holes, double[] triangles) { }
    record Node(double x,double y,double px,double py,double angle, JsonObject parent, JsonObject options,List<Paint> paints) { }
    private final List<Node> nodes = new ArrayList<>();
    VectorComposition(JsonObject source) { this(single(source)); }
    private static JsonArray single(JsonObject object) { var a=new JsonArray(); a.add(object); return a; }
    VectorComposition(JsonArray source) {
        for (JsonElement item : source) {
            JsonObject node = item.getAsJsonObject();
            var paints = new ArrayList<Paint>();
            for (JsonElement value : array(node, "commands")) {
                JsonObject command = value.getAsJsonObject(), style = object(command,"style");
                List<Path> paths=paths(array(command,"paths")), holes=paths(array(command,"holes"));
                boolean fill = s(command,"action","").equals("fill");
                paints.add(new Paint((int)n(style,"color",0xffffff) | 0xff000000,n(style,"alpha",1),
                        fill ? 0 : n(style,"width",1),paths,holes,fill?tessellate(paths,holes):new double[0]));
            }
            nodes.add(new Node(n(node,"x",0),n(node,"y",0),n(node,"px",0),n(node,"py",0),n(node,"rotation",0),
                    object(node,"parent"),object(node,"options"),paints));
        }
    }
    private static List<Path> paths(JsonArray list) {
        var result = new ArrayList<Path>();
        for (JsonElement el:list) {
            JsonObject p=el.getAsJsonObject(); String kind=s(p,"kind","");
            if(kind.equals("circle")) {
                double[] xy=new double[96];
                for(int i=0;i<48;i++){double a=i*Math.PI/24;xy[i*2]=n(p,"x",0)+Math.cos(a)*n(p,"radius",0);xy[i*2+1]=n(p,"y",0)+Math.sin(a)*n(p,"radius",0);}
                result.add(new Path(xy,true));
            } else {
                JsonArray a=array(p,"points");double[] xy=new double[a.size()];for(int i=0;i<xy.length;i++)xy[i]=a.get(i).getAsDouble();
                result.add(new Path(xy,kind.equals("poly")));
            }
        }
        return result;
    }
    private static double[] tessellate(List<Path> paths,List<Path> holes) {
        var vertices=new ArrayList<Double>();
        var tess=GLU.gluNewTess();
        var callback=new GLUtessellatorCallbackAdapter(){
            int mode; final List<double[]> strip=new ArrayList<>();
            @Override public void begin(int type){mode=type;strip.clear();}
            @Override public void vertex(Object data){
                strip.add((double[])data);int n=strip.size();
                if(n<3)return;
                if(mode==GL11.GL_TRIANGLES&&n%3!=0)return;
                double[] a=strip.get(mode==GL11.GL_TRIANGLE_FAN?0:n-3), bb=strip.get(n-2), c=strip.get(n-1);
                for(double[] v:new double[][]{a,bb,c}){vertices.add(v[0]);vertices.add(v[1]);}
            }
            @Override public void combine(double[] coords,Object[] data,float[] weight,Object[] output){output[0]=coords.clone();}
            @Override public void error(int error){throw new IllegalArgumentException("Invalid Folia vector: "+error);}
        };
        tess.gluTessCallback(GLU.GLU_TESS_BEGIN,callback);tess.gluTessCallback(GLU.GLU_TESS_VERTEX,callback);
        tess.gluTessCallback(GLU.GLU_TESS_COMBINE,callback);tess.gluTessCallback(GLU.GLU_TESS_ERROR,callback);
        tess.gluTessProperty(GLU.GLU_TESS_WINDING_RULE,GLU.GLU_TESS_WINDING_ODD);
        try {
            // Independent discs may overlap. Only polygon cutouts share a tessellation.
            for(Path path:paths){
                tess.gluTessBeginPolygon(null); contour(tess,path);
                for(Path hole:holes)contour(tess,hole);
                tess.gluTessEndPolygon();
            }
        } finally {tess.gluDeleteTess();}
        return vertices.stream().mapToDouble(Double::doubleValue).toArray();
    }
    private static void contour(org.lwjgl.util.glu.GLUtessellator tess,Path p){
        tess.gluTessBeginContour();for(int i=0;i+1<p.xy.length;i+=2){double[] v={p.xy[i],p.xy[i+1],0};tess.gluTessVertex(v,0,v);}tess.gluTessEndContour();
    }
    void draw(LyricCanvas g,LyricFrame f,double local,double duration,double fade) {
        double old=g.opacity;g.opacity*=fade;
        GL11.glPushMatrix();GL11.glScaled(f.width()/1280,f.contentHeight()/720,1);
        double progress=clamp(local/Math.max(.2,duration)),carry=1280*.09,creep=inOut(progress)*carry*.35;
        try {int index=0;for(Node node:nodes){
            JsonObject o=node.options;
            double delay=Math.max(0,Math.min(1.4,duration*n(o,"delay",0))),span=Math.max(.7,Math.min(2.6,duration*n(o,"span",.45)));
            double compress=Math.min(1,Math.max(.5,duration)/(delay+span));
            double enter=out((local-delay*compress)/(span*compress));if(enter<.001)continue;
            double behind=(1-enter)*carry-creep,phase=random(index+++137)*Math.PI*2;
            g.push(n(node.parent,"x",0),n(node.parent,"y",0),1,Math.toDegrees(n(node.parent,"rotation",0)));
            double drift=b(o,"drift")?1+Math.sin(f.time()*.5+phase)*.02:1;
            g.push(node.x+n(o,"enterDX",0)*(1-enter)-Math.cos(-.24)*behind,
                    node.y+n(o,"enterDY",0)*(1-enter)-Math.sin(-.24)*behind,drift,
                    Math.toDegrees(node.angle+(b(o,"drift")?Math.sin(f.time()*.33+phase)*.012:0)));
            if(b(o,"grow"))GL11.glScaled(Math.max(.0001,enter),1,1);
            GL11.glTranslated(-node.px,-node.py,0);
            for(Paint paint:node.paints){
                int base=color(0xff15121e,f.accent(),(paint.color&255)/255.0*.38);
                base=color(base,0xfff4f1ef,Math.pow((paint.color&255)/255.0,1.5));
                int ink=alpha(base,paint.alpha*enter*n(o,"alpha",1));
                if(paint.width==0)g.triangles(ink,paint.triangles);
                else for(Path path:paint.paths){
                    for(int i=2;i<path.xy.length;i+=2)g.line(path.xy[i-2],path.xy[i-1],path.xy[i],path.xy[i+1],paint.width,ink);
                    if(path.closed&&path.xy.length>=4)g.line(path.xy[path.xy.length-2],path.xy[path.xy.length-1],path.xy[0],path.xy[1],paint.width,ink);
                }
            }
            g.pop();g.pop();
        }} finally{GL11.glPopMatrix();g.opacity=old;}
    }
    /** Sonnet's golden-ratio stroke schedule: each stroke grows for its own complete window. */
    void drawArt(LyricCanvas g,double progress,double opacity,int accent) {
        int stroke=0,fill=0;
        for(Node node:nodes){
            g.push(node.x,node.y,1,Math.toDegrees(node.angle));
            for(Paint paint:node.paints){
                int index=paint.width>0?stroke++:fill++;
                double slot=(index*.6180339887498949)%1,jitter=Integer.toUnsignedLong(index*(int)2654435761L)/4294967296.0;
                double delay=slot*(paint.width>0?.5:.45),span=Math.min(1-delay,(paint.width>0?.32:.4)+jitter*(paint.width>0?.26:.25));
                double reveal=out((progress-delay)/span);
                if(reveal<=0)continue;
                int color=alpha(color(accent,0xfff2eee9,(paint.color&255)/255.0),paint.alpha*opacity);
                if(paint.width==0)g.triangles(alpha(color,reveal),paint.triangles);
                else for(Path path:paint.paths){
                    double segments=(path.xy.length/2-1+(path.closed?1:0))*reveal;
                    for(int i=0;i<Math.ceil(segments);i++){
                        int start=i*2,end=((i+1)%(path.xy.length/2))*2;
                        double part=clamp(segments-i);
                        g.line(path.xy[start],path.xy[start+1],mix(path.xy[start],path.xy[end],part),mix(path.xy[start+1],path.xy[end+1],part),paint.width,color);
                    }
                }
            }
            g.pop();
        }
    }
}
