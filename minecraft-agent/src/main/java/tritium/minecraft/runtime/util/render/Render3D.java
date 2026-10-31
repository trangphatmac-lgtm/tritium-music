package tritium.minecraft.runtime.util.render;
import java.awt.Color;
import java.nio.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import tritium.minecraft.runtime.hook.RenderAccess;
/** OpenB4 geometry, projection and color utilities, independent of game class linkage. */
public final class Render3D {
 public final RenderAccess g;
 private final FloatBuffer model=BufferUtils.createFloatBuffer(16),projection=BufferUtils.createFloatBuffer(16);
 private final IntBuffer viewport=BufferUtils.createIntBuffer(16);
 private Object frustum;
 public final double[] camera=new double[3];
 public Render3D(RenderAccess g){this.g=g;}
 public void update(float pt){
  if(frustum==null)frustum=g.make("client.renderer.culling.Frustum");Object e=g.invoke(g.mc,"getRenderViewEntity");
  if(e!=null)g.invoke(frustum,"setPosition",g.interp(e,0,pt),g.interp(e,1,pt),g.interp(e,2,pt));
  camera[0]=g.view(0);camera[1]=g.view(1)+g.eye(g.player());camera[2]=g.view(2);
  if(g.integerField(g.settings(),"thirdPersonView")!=0){Class<?> a=g.cls("client.renderer.ActiveRenderInfo");double yaw=Math.atan2(RenderAccess.f(g.invoke(a,"getRotationXY")),RenderAccess.f(g.invoke(a,"getRotationX"))),pitch=Math.acos(RenderAccess.f(g.invoke(a,"getRotationXZ")));double d=Math.cos(pitch)*4;camera[0]-=-Math.sin(yaw)*d;camera[1]-=-Math.sin(pitch)*4;camera[2]-=Math.cos(yaw)*d;}
 }
 public boolean visible(Object entity){return RenderAccess.b(g.invoke(frustum,"isBoundingBoxInFrustum",g.entityBox(entity)));}
 public boolean visible(double[] bb){return RenderAccess.b(g.invoke(frustum,"isBoxInFrustum",bb[0],bb[1],bb[2],bb[3],bb[4],bb[5]));}
 public void setupCamera(float pt){g.invoke(g.renderer(),"setupCameraTransform",pt,0);}
 public void overlay(){GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();GL11.glOrtho(0,g.width(),g.height(),0,1000,3000);GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();GL11.glTranslatef(0,0,-2000);}
 public double[] project(double x,double y,double z,float pt){
  if(pt!=-1)setupCamera(pt);GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX,model);GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX,projection);GL11.glGetInteger(GL11.GL_VIEWPORT,viewport);
  double[] v={x-g.view(0),y-g.view(1),z-g.view(2),1},a=new double[4],c=new double[4];
  for(int i=0;i<4;i++)for(int j=0;j<4;j++)a[i]+=model.get(j*4+i)*v[j];for(int i=0;i<4;i++)for(int j=0;j<4;j++)c[i]+=projection.get(j*4+i)*a[j];
  overlay();if(c[3]==0)return null;int scale=g.scale();return new double[]{(viewport.get(0)+(1+c[0]/c[3])*viewport.get(2)/2)/scale,(g.displayHeight()-(viewport.get(1)+(1+c[1]/c[3])*viewport.get(3)/2))/scale,(1+c[2]/c[3])/2};
 }
 public static int rainbow(long speed,long... offset){long l=System.currentTimeMillis()+(offset.length>0?offset[0]:0);return Color.getHSBColor((float)(l%(15000L/speed))/(15000f/speed),.75f,1).getRGB();}
 public static int blend(int a,int b,double d){int n=(int)(d*255),m=255-n;return 0xff000000|((((a>>16)&255)*n+((b>>16)&255)*m)>>8)<<16|((((a>>8)&255)*n+((b>>8)&255)*m)>>8)<<8|(((a&255)*n+(b&255)*m)>>8);}
 public static int alpha(int c,int a){return c&0xffffff|a<<24;}
 public static void color(int c){GL11.glColor4f((c>>16&255)/255f,(c>>8&255)/255f,(c&255)/255f,(c>>>24)/255f);}
 public static int healthColor(double d){return d<.3?-55770:d<.5?-12250:d<.7?-218:-14221530;}
 public static void begin(){GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(770,771);GL11.glDisable(GL11.GL_TEXTURE_2D);}
 public static void through(){begin();GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);}
 public static void rect(double x,double y,double x2,double y2,int c){begin();color(c);GL11.glBegin(GL11.GL_QUADS);GL11.glVertex2d(x,y);GL11.glVertex2d(x,y2);GL11.glVertex2d(x2,y2);GL11.glVertex2d(x2,y);GL11.glEnd();GL11.glEnable(GL11.GL_TEXTURE_2D);}
 public static void gradient(double x,double y,double x2,double y2,int c,int c2){begin();GL11.glShadeModel(GL11.GL_SMOOTH);GL11.glBegin(GL11.GL_QUADS);color(c);GL11.glVertex2d(x,y);GL11.glVertex2d(x2,y);color(c2);GL11.glVertex2d(x2,y2);GL11.glVertex2d(x,y2);GL11.glEnd();GL11.glShadeModel(GL11.GL_FLAT);GL11.glEnable(GL11.GL_TEXTURE_2D);}
 public static void round(double x,double y,double x2,double y2,double radius,int c){roundedGradient(x,y,x2,y2,radius,c,c);}
 public static void roundedGradient(double x,double y,double x2,double y2,double radius,int left,int right){
  begin();GL11.glShadeModel(GL11.GL_SMOOTH);GL11.glEnable(GL11.GL_LINE_SMOOTH);double r=radius/2;
  GL11.glBegin(GL11.GL_POLYGON);for(int corner=0;corner<4;corner++){double cx=corner<2?x2-r:x+r,cy=corner==0||corner==3?y+r:y2-r;for(int deg=0;deg<=90;deg+=3){double a=Math.toRadians(corner*90+deg-90),px=cx+Math.cos(a)*r,py=cy+Math.sin(a)*r;double q=(px-x)/Math.max(.001,x2-x);color(blend(right,left,q));GL11.glVertex2d(px,py);}}GL11.glEnd();GL11.glDisable(GL11.GL_LINE_SMOOTH);GL11.glShadeModel(GL11.GL_FLAT);GL11.glEnable(GL11.GL_TEXTURE_2D);
 }
 public static void roundedBorder(double x,double y,double x2,double y2,double r,int fill,int left,int right){RenderShapes.roundedRectBorder((float)x,(float)y,(float)x2,(float)y2,(float)r,fill,left,right);}
 public static void filled(double[] b,int c,float alpha){through();color(alpha(c,(int)(alpha*255)));GL11.glBegin(GL11.GL_QUADS);int[][] faces={{0,2,6,4},{1,5,7,3},{0,4,5,1},{2,3,7,6},{0,1,3,2},{4,6,7,5}};for(int[] face:faces)for(int v:face)GL11.glVertex3d(b[(v&4)==0?0:3],b[(v&2)==0?1:4],b[(v&1)==0?2:5]);GL11.glEnd();}
 public static void outline(double[] b,int c,float width){through();color(c);GL11.glEnable(GL11.GL_LINE_SMOOTH);GL11.glLineWidth(width);GL11.glBegin(GL11.GL_LINES);for(int v=0;v<8;v++)for(int axis=0;axis<3;axis++){int w=v^(1<<axis);if(v<w){GL11.glVertex3d(b[(v&4)==0?0:3],b[(v&2)==0?1:4],b[(v&1)==0?2:5]);GL11.glVertex3d(b[(w&4)==0?0:3],b[(w&2)==0?1:4],b[(w&1)==0?2:5]);}}GL11.glEnd();}
 public void blockBox(double x,double y,double z,double a,double b,double c,double d,double e,double f,int color,boolean outline,boolean shade){double[] bb={x-g.view(0)+a,y-g.view(1)+b,z-g.view(2)+c,x-g.view(0)+d,y-g.view(1)+e,z-g.view(2)+f};if(outline)outline(bb,color,2);if(shade)filled(bb,color,.42f);}
 public void block(Object pos,int color,boolean outline,boolean shade){blockBox(g.coord(pos,0),g.coord(pos,1),g.coord(pos,2),0,0,0,1,1,1,color,outline,shade);}
 public void face(Object pos,Object face,int color){double[] b={0,0,0,1,1,1};int f=((Enum<?>)face).ordinal();if(f==0)b[4]=0;if(f==1)b[1]=1;if(f==2)b[5]=0;if(f==3)b[2]=1;if(f==4)b[3]=0;if(f==5)b[0]=1;blockBox(g.coord(pos,0),g.coord(pos,1),g.coord(pos,2),b[0],b[1],b[2],b[3],b[4],b[5],color,true,false);}
 public static void circle(double x,double y,double radius,int vertices,int c){begin();color(c);GL11.glBegin(GL11.GL_TRIANGLE_FAN);for(int j=0;j<vertices;j++){double a=Math.PI*2*j/vertices+Math.PI;GL11.glVertex2d(x+Math.sin(a)*radius,y+Math.cos(a)*radius);}GL11.glEnd();GL11.glEnable(GL11.GL_TEXTURE_2D);}
 public void entity(Object e,int mode,int c,float pt){
  double x=g.interp(e,0,pt)-g.view(0),y=g.interp(e,1,pt)-g.view(1),z=g.interp(e,2,pt)-g.view(2);GL11.glPushMatrix();try{
   if(mode==3||mode==4){GL11.glTranslated(x,y-(mode==3?.2:.1),z);GL11.glRotated(-g.value(g.manager(),"playerViewY"),0,1,0);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glScalef(.03f,.03f,.03f);
    if(mode==4){double ratio=Math.min(RenderAccess.d(g.invoke(e,"getHealth"))/g.maxHealth(e),1);boolean child=RenderAccess.b(g.invoke(e,"isChild"));float h=g.value(e,"height")<1.1||child?40:70;int bx=child?16:21;rect(bx,-1,bx+4,h+1,-15987700);rect(bx+1,h*ratio,bx+3,h,-11974327);rect(bx+1,0,bx+3,h*ratio,healthColor(ratio));}
    else {rect(-20.5,75,20.5,75.5,-15987700);rect(-20.5,-1.5,20.5,-1,-15987700);rect(-20.5,-1,-20,75,-15987700);rect(20.5,-1,20,75,-15987700);int top=c==0?rainbow(2,0):c,bottom=c==0?rainbow(2,1000):c;gradient(-20,.5,-18.5,73.5,top,bottom);gradient(20,.5,18.5,73.5,top,bottom);rect(-20,75,20,73.5,top);rect(-20,-1,20,.5,bottom);}
   }else if(mode==6){through();GL11.glEnable(GL11.GL_LINE_SMOOTH);GL11.glLineWidth(1.5f);GL11.glBegin(GL11.GL_LINES);long off=0,step=15000/45;for(int j=0;j<90;j++){if(c==0){if(j%2!=0){if(j==47)off=step/2;off+=step;}color(rainbow(2,off));}else color(c);double a=Math.PI*2*j/45+Math.PI;GL11.glVertex3d(x+Math.cos(a)*.7,y,z+Math.sin(a)*.7);}GL11.glEnd();}
   else {double[] bb=g.bounds(g.entityBox(e));for(int j=0;j<6;j++)bb[j]+=new double[]{x,y,z}[j%3]-g.pos(e,j%3)+(j<3?-.1:.1);if(c==0)c=rainbow(2);if(mode==1)outline(bb,c,2);else if(mode==2)filled(bb,c,.37f);}
  }finally{GL11.glPopMatrix();}
 }
 public void tracer(Object e,float width,int c,float pt){through();color(c);GL11.glEnable(GL11.GL_LINE_SMOOTH);GL11.glLineWidth(width);GL11.glBegin(GL11.GL_LINE_LOOP);GL11.glVertex3d(0,g.eye(g.player()),0);GL11.glVertex3d(g.interp(e,0,pt)-g.view(0),g.interp(e,1,pt)+g.eye(e)-.4-g.view(1),g.interp(e,2,pt)-g.view(2));GL11.glEnd();}
 public void beam(double x,double y,double z,double height,double width,int c,float alpha){double[] b={x+.5-width-g.view(0),y-g.view(1),z+.5-width-g.view(2),x+.5+width-g.view(0),y+height-g.view(1),z+.5+width-g.view(2)};begin();GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDepthMask(false);color(alpha(c,(int)(alpha*255)));GL11.glBegin(GL11.GL_QUADS);int[][] faces={{0,2,6,4},{1,5,7,3},{0,1,3,2},{4,6,7,5}};for(int[] face:faces)for(int v:face)GL11.glVertex3d(b[(v&4)==0?0:3],b[(v&2)==0?1:4],b[(v&1)==0?2:5]);GL11.glEnd();GL11.glDepthMask(true);GL11.glEnable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_BLEND);GL11.glEnable(GL11.GL_TEXTURE_2D);}
 public void quad(){quad(0,0,g.width(),g.height());}
 public static void quad(double x,double y,double w,double h){GL11.glBegin(GL11.GL_QUADS);GL11.glTexCoord2d(0,1);GL11.glVertex2d(x,y);GL11.glTexCoord2d(0,0);GL11.glVertex2d(x,y+h);GL11.glTexCoord2d(1,0);GL11.glVertex2d(x+w,y+h);GL11.glTexCoord2d(1,1);GL11.glVertex2d(x+w,y);GL11.glEnd();}
}
