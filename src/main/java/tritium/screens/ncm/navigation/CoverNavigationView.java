// SPDX-License-Identifier: AGPL-3.0-only
// Folia Grid3DSlider, hexViewport, Bravais camera/hover/reflow/flip and morph transitions.
// See tritium/licenses/Folia-NOTICE.txt for the corresponding source files.
package tritium.screens.ncm.navigation;

import org.lwjgl.input.Keyboard;
import tritium.desktop.AppTheme;
import tritium.desktop.DesktopAppState;
import tritium.desktop.NavigationPreferences;
import tritium.i18n.I18n;
import tritium.interfaces.SharedConstants;
import tritium.interfaces.SharedRenderingConstants;
import tritium.management.FontManager;
import tritium.ncm.music.CloudMusic;
import tritium.rendering.Rect;
import tritium.rendering.StencilClipManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.shader.Shaders;
import tritium.screens.ncm.CoverflowOverlay;
import tritium.screens.ncm.NCMScreen;
import tritium.utils.KeyboardUtils;
import java.util.*;
import static tritium.screens.ncm.navigation.FoliaMotion.*;

/** Cover surfaces occupy the entire content viewport. Navigation and search belong to NCMScreen. */
public final class CoverNavigationView implements SharedConstants, SharedRenderingConstants {
    private final NavigationContent content;
    private final NavigationPreferences preferences=DesktopAppState.preferences().navigation();
    private final CoverflowOverlay flow=new CoverflowOverlay();
    private final FoliaCoverRenderer renderer=new FoliaCoverRenderer();
    private List<NavigationContent.Entry> source=List.of(),entries=List.of();
    private List<FoliaGeometry.Box> geometry=List.of();
    private final List<FoliaGeometry.Box> hits=new ArrayList<>();
    private final List<Painted> painted=new ArrayList<>();
    private final Map<Object,Double> hovers=new HashMap<>();
    private List<Painted> previous=List.of();
    private Map<Object,Painted> previousById=Map.of();
    private Painted flightFrom;
    private String query="",layoutKey="";
    private int selected,wallOffset;
    private FoliaWall.Address startAddress;
    private double x,y,width,height,cameraX,cameraY,targetX,targetY,zoom=1,wallScale;
    private boolean entered,dragging,moved,keyboardFocus,opening,flip,wasWall,preserveCamera;
    private double pressX,pressY,pressCameraX,pressCameraY;
    private double safeX,safeY,safeWidth,safeHeight;
    private Object pressedId,focusedId;
    private FoliaWall.Address expanded;
    private Map<FoliaWall.Address,FoliaGeometry.Box> reflowFrom=Map.of();
    private long transitionAt=-1,reflowAt;
    private record Painted(Object id,NavigationContent.Entry entry,FoliaGeometry.Box box,float alpha,double hover,boolean wall,boolean library) { }

    public CoverNavigationView(NavigationContent content) { this.content=content; }
    public void setSafeArea(double x,double y,double width,double height) {
        safeX=x; safeY=y; safeWidth=width; safeHeight=height;
    }
    private double cardCenterX() { return safeWidth>0?safeX+safeWidth/2:x+width/2; }
    private double cardCenterY() { return safeHeight>0?safeY+safeHeight/2:y+height/2; }
    public NavigationContent content() { return content; }
    public void enter() { enter(null); }
    public void enter(CoverNavigationView from) {
        entered=true; dragging=false; opening=false; keyboardFocus=false; transitionAt=-1;
        wasWall=preferences.isWall();
        if(!preferences.rememberPosition().getValue()) { selected=0; layoutKey=""; }
        previous=from==null||from==this?List.of():List.copyOf(from.painted);
        previousById=new HashMap<>(); for(var p:previous) previousById.put(p.id,p);
        flip=from!=null&&from!=this&&from.opening&&from.wasWall&&wasWall;
        startAddress=flip&&from.focusedId instanceof FoliaWall.Address a?a:null;
        flightFrom=from==null||from==this?null:from.focusedPaint();
        if(from!=null&&from.wasWall&&wasWall&&from.opening) {
            cameraX=targetX=from.cameraX; cameraY=targetY=from.cameraY; zoom=from.zoom;
            preserveCamera=true; layoutKey="";
        }
    }
    public void leave() { dragging=false; entered=false; flow.onGuiClosed(); }
    public void setQuery(String query) {
        if(this.query.equals(query)) return;
        this.query=query;
        rememberDisplay(); filter();
    }
    private void rememberDisplay() {
        if(!painted.isEmpty()) {
            previous=List.copyOf(painted); previousById=new HashMap<>();
            for(var p:previous) previousById.put(p.id,p);
            flip=preferences.isWall(); transitionAt=-1;
        }
    }
    private void refresh() {
        var current=content.entries();
        if(source!=current) {
            String key=entries.isEmpty()?preferences.position(content.key()):entries.get(Math.min(selected,entries.size()-1)).key();
            source=current; filter();
            if (flip && !previous.isEmpty()) preserveCamera=true;
            if(preferences.rememberPosition().getValue())
                for(int i=0;i<entries.size();i++) if(entries.get(i).key().equals(key)) selected=i;
        }
    }
    private void filter() {
        entries=source.stream().filter(e->e.matches(query)).toList(); selected=0;
        layoutKey=""; expanded=null; focusedId=null; wallOffset=0; flow.setEntries(entries);
    }
    public void render(double x,double y,double width,double height,double mouseX,double mouseY,int wheel) {
        this.x=x; this.y=y; this.width=width; this.height=height;
        if(!entered) enter(); refresh();
        if(preferences.isFullscreen()) {
            if(wheel!=0) select(selected+(wheel<0?1:-1));
            flow.setIndex(selected); flow.setOldSchool(preferences.coverflow().getValue().equals("OldSchool")); flow.renderScene();
            return;
        }
        long now=System.nanoTime()/1_000_000;
        if(transitionAt<0&&(!entries.isEmpty()||!content.loading())) transitionAt=now;
        double age=transitionAt<0?0:now-transitionAt;
        layout(); handleCamera(mouseX,mouseY,wheel);
        StencilClipManager.beginClip(()->Rect.draw(x,y,width,height,-1));
        if(preferences.isWall()) drawWallBackdrop();
        if(entries.isEmpty()&&content.loading()&&!previous.isEmpty()) {
            for(var p:previous) renderer.draw(p.entry,p.box,p.alpha,p.hover,false,p.wall,p.library,false,8*wallScale);
        } else {
            painted.clear(); hits.clear();
            if(preferences.isWall()) drawWall(mouseX,mouseY,age,now);
            else drawCards(mouseX,mouseY,age);
        }
        StencilClipManager.endClip();
        if(entries.isEmpty()) FontManager.pf20bold.drawCenteredString(I18n.text(content.loading()?"library.loading":"navigation.empty"),
                x+width/2,y+height*.5,AppTheme.foreground());
        if(age>1500) { previous=List.of(); previousById=Map.of(); flightFrom=null; }
    }
    private void layout() {
        String key=width+":"+height+":"+safeWidth+":"+safeHeight+":"+entries.size()+":"+preferences.mode().getValue()+":"+preferences.cardStyle().getValue()
                +":"+preferences.squareCards().getValue()+":"+preferences.wallLook().getValue()+":"+preferences.wallWindows().getValue()+":"+zoom;
        if(key.equals(layoutKey)) return;
        boolean first=layoutKey.isEmpty(); layoutKey=key;
        double cardHeight=safeHeight>0?safeHeight:height;
        if(preferences.isWall()) {
            // Folia CSS coordinates are twice this renderer's font/layout units.
            double nextScale=(width<320?.26:width<550?.32:.38)*zoom;
            if(wallScale>0&&!first) { cameraX*=nextScale/wallScale; cameraY*=nextScale/wallScale; targetX*=nextScale/wallScale; targetY*=nextScale/wallScale; }
            wallScale=nextScale;
            if(startAddress!=null&&!entries.isEmpty()) {
                int index=FoliaWall.box(startAddress,entries.size(),windows(),wallScale,-1).index();
                wallOffset=index<0?0:Math.floorMod(selected-index,entries.size());
                focusedId=startAddress; startAddress=null;
            }
        } else if(content.isCollectionLibrary()) {
            boolean image=preferences.cardStyle().getValue().equals("image");
            double w=Math.max(60,Math.min(width*.28,(image?(cardHeight-65)/1.25:(cardHeight-65)/1.2-34)));
            double h=image?w:w+34;
            List<FoliaGeometry.Box> row=new ArrayList<>();
            for(int i=0;i<entries.size();i++) row.add(new FoliaGeometry.Box(i,i*(w+24)-w/2,-h/2,w,h));
            geometry=row;
        } else {
            double w=Math.max(70,Math.min(width*.24,cardHeight*.43));
            boolean square=preferences.cardStyle().getValue().equals("image")||preferences.squareCards().getValue();
            geometry=FoliaGeometry.cards(entries.size(),w,square?w:w*1.4);
        }
        if(entries.isEmpty()) return;
        if(!preserveCamera) { focusCamera(); if(first) { cameraX=targetX; cameraY=targetY; } }
        preserveCamera=false;
    }
    private int windows() { return preferences.wallLook().getValue().equals("partial")?preferences.wallWindows().getValue().intValue():0; }
    private void focusCamera() {
        if(preferences.isWall()) {
            var tile=FoliaWall.nearest(selected-wallOffset,entries.size(),windows(),Math.max(.01,wallScale),cameraX,cameraY);
            if(tile!=null) { targetX=tile.box().centerX(); targetY=tile.box().centerY(); focusedId=tile.address(); }
        } else if(!geometry.isEmpty()) {
            var b=geometry.get(Math.min(selected,geometry.size()-1)); targetX=b.centerX(); targetY=b.centerY(); focusedId=b.index();
        }
    }
    private void handleCamera(double mx,double my,int wheel) {
        if(wheel!=0&&inside(mx,my)) {
            if(preferences.isWall()) {
                if(KeyboardUtils.isCtrlKeyDown()) { zoom=Math.clamp(zoom*(wheel>0?1.1:1/1.1),.6,2.5); layoutKey="zoom"; }
                else targetY+=wheel<0?65:-65;
            } else select(selected+(wheel<0?1:-1));
        }
        if(dragging) {
            moved|=Math.hypot(mx-pressX,my-pressY)>4;
            if(moved) {
                targetX=pressCameraX-(mx-pressX); targetY=content.isCollectionLibrary()&&!preferences.isWall()?0:pressCameraY-(my-pressY);
                if(!preferences.isWall()&&content.isCollectionLibrary()&&!geometry.isEmpty()) targetX=Math.clamp(targetX,0,geometry.getLast().centerX());
                cameraX=targetX; cameraY=targetY;
            }
        }
        cameraX=Interpolations.interpolate(cameraX,targetX,.17); cameraY=Interpolations.interpolate(cameraY,targetY,.17);
    }
    private void drawWallBackdrop() {
        // NCMScreen already rendered the user's background. Solid describes the
        // covers, not a backing plate: transitions must reveal that same scene.
        if(preferences.backdropLyrics().getValue()&&CloudMusic.currentLyric!=null)
            FontManager.pf32.drawCenteredStringWithShadow(FontManager.pf32.trim(CloudMusic.currentLyric.getLyric(),width-40),x+width/2,y+height/2,AppTheme.foreground());
        if(preferences.backdropBlur().getValue()) Shaders.BLUR_SHADER.run(List.of(()->Rect.draw(x,y,width,height,-1)));
    }
    private FoliaGeometry.Box wallIndex(FoliaGeometry.Box b) {
        return new FoliaGeometry.Box(b.index()<0?-1:Math.floorMod(b.index()+wallOffset,entries.size()),b.x(),b.y(),b.width(),b.height());
    }
    private FoliaGeometry.Box screenBox(FoliaGeometry.Box b) {
        var screen=new FoliaGeometry.Box(b.index(),x+width/2+b.x()-cameraX,y+height/2+b.y()-cameraY,b.width(),b.height());
        return FoliaWallSeams.snap(screen,8*wallScale,RenderSystem.getFramebufferWidth()/RenderSystem.getWidth());
    }
    private void drawWall(double mx,double my,double age,long now) {
        var tiles=FoliaWall.visible(entries.size(),windows(),wallScale,cameraX,cameraY,width,height);
        List<Painted> scene=new ArrayList<>();
        double reflow=reflow(now-reflowAt);
        for(var tile:tiles) {
            var b=tile.box(); var a=tile.address();
            if(expanded!=null&&a.column()==expanded.column()&&a.row()==expanded.row()) b=FoliaWall.box(a,entries.size(),windows(),wallScale,expanded.slot());
            if(reflow<1&&reflowFrom.containsKey(a)) b=mix(reflowFrom.get(a),b,reflow);
            if(b.index()<0) continue;
            var draw=screenBox(wallIndex(b));
            if(draw.x()>x+width+12||draw.x()+draw.width()<x-12||draw.y()>y+height+12||draw.y()+draw.height()<y-12) continue;
            double target=(inside(mx,my)&&draw.contains(mx,my)&&!moved)||(keyboardFocus&&a.equals(focusedId))?1:0;
            double hover=Interpolations.interpolate(hovers.getOrDefault(a,0.0),target,.15);
            hovers.put(a,hover);
            double pop=8*wallScale*hover;
            draw=new FoliaGeometry.Box(draw.index(),draw.x()-pop,draw.y()-pop,draw.width()+pop*2,draw.height()+pop*2);
            scene.add(new Painted(a,entries.get(draw.index()),draw,1,hover,true,false));
        }
        scene.sort(Comparator.comparingDouble(p->p.id.equals(expanded)?2:p.hover));
        for(var tile:scene) {
            double delay=Math.min(340,(Math.max(0,tile.box.x()-x)+Math.max(0,tile.box.y()-y))/(136*wallScale)*30);
            var from=previousById.get(tile.id);
            if(flip&&from!=null&&age<720) {
                double distance=flightFrom==null?delay:Math.min(340,Math.hypot(tile.box.centerX()-flightFrom.box.centerX(),tile.box.centerY()-flightFrom.box.centerY())/(136*wallScale)*30);
                double t=age-distance;
                boolean old=t<150;
                double amount=t<0?1:old?Math.cos(unit(t/150)*Math.PI/2):Math.sin(ease((t-150)/210)*Math.PI/2);
                var face=old?new Painted(tile.id,from.entry,tile.box,1,from.hover,true,false):tile;
                api.getGLStateManager().pushMatrix();
                api.getGLStateManager().translate(tile.box.centerX(),tile.box.centerY(),0);
                api.getGLStateManager().scale(Math.max(.002,amount),1,1);
                api.getGLStateManager().translate(-tile.box.centerX(),-tile.box.centerY(),0);
                paint(face); api.getGLStateManager().popMatrix();
                hits.add(tile.box);
            } else if(!flip&&age<1380) {
                double start=previous.isEmpty()?0:620;
                if(from!=null&&age<620) {
                    double p=ease((age-(340-delay))/280);
                    paint(shift(from,0,-45*p,1-.12*p,(float)(1-p)));
                }
                double p=ease((age-start-delay)/420);
                paint(shift(tile,0,-45*(1-p),.88+.12*p,(float)p)); hits.add(tile.box);
            } else { paint(tile); hits.add(tile.box); }
        }
        // Only visible tile hover state is retained while roaming an unbounded field.
        if(hovers.size()>500) hovers.keySet().retainAll(scene.stream().map(Painted::id).toList());
    }
    private void drawCards(double mx,double my,double age) {
        boolean library=content.isCollectionLibrary(),image=preferences.cardStyle().getValue().equals("image");
        List<Painted> scene=new ArrayList<>();
        for(var b:geometry) {
            double dx=b.centerX()-cameraX,dy=b.centerY()-cameraY;
            if(Math.abs(dx)>width/2+b.width()*2||Math.abs(dy)>height/2+b.height()*2) continue;
            double distance=library?Math.abs(dx):Math.hypot(dx,dy);
            double fraction=unit(distance/Math.max(300,width*.52));
            var falloff=FoliaGeometry.falloff(distance,Math.max(220,Math.hypot(width,height)*.48),preferences.minScale().getValue(),preferences.minOpacity().getValue());
            double scale=library?lerp(image?1.25:1.2,.5,fraction):falloff.scale();
            float alpha=library?(float)Math.max(.15,1-.85*fraction):falloff.opacity();
            var draw=new FoliaGeometry.Box(b.index(),cardCenterX()+dx-b.width()*scale/2,
                    cardCenterY()+dy-b.height()*scale/2-(library?18+3*(1-fraction):0),b.width()*scale,b.height()*scale);
            boolean over=inside(mx,my)&&draw.contains(mx,my)&&!moved;
            double hover=Interpolations.interpolate(hovers.getOrDefault(b.index(),0.0),over?1:0,.16);
            hovers.put(b.index(),hover);
            draw=new FoliaGeometry.Box(b.index(),draw.x(),draw.y()-hover*3,draw.width(),draw.height());
            scene.add(new Painted(b.index(),entries.get(b.index()),draw,alpha,hover,false,library));
        }
        scene.sort(Comparator.comparingDouble(p->-Math.abs(p.box.centerX()-(cardCenterX()))));
        if(!previous.isEmpty()&&age<460) for(var p:previous) {
            if(p==flightFrom) continue;
            double t=ease(age/420),dx=p.box.centerX()-(cardCenterX()),dy=p.box.centerY()-(cardCenterY());
            paint(shift(p,dx*t*.45,dy*t*.45,.94,(float)(1-t)));
        }
        Painted hero=null;
        for(var tile:scene) {
            if(tile.box.index()==selected) hero=tile;
            double d=Math.hypot(tile.box.centerX()-(cardCenterX()),tile.box.centerY()-(cardCenterY()));
            double delay=tile.box.index()==selected?0:Math.min(320,40+d/Math.max(1,width)*340);
            double p=ease((age-delay)/500);
            double angle=Math.atan2(tile.box.centerY()-(cardCenterY()),tile.box.centerX()-(cardCenterX()));
            double travel=Math.max(80,Math.hypot(width,height)*.25)*(1-p);
            paint(shift(tile,Math.cos(angle)*travel,Math.sin(angle)*travel,.94+.06*p,(float)p));
            hits.add(tile.box);
        }
        if(hero!=null&&flightFrom!=null&&age<600) {
            var box=mix(flightFrom.box,hero.box,flight(age));
            // Retain the opened sleeve throughout the flight, then hand it to the first song.
            paint(new Painted(hero.id,library?hero.entry:flightFrom.entry,box,(float)(1-unit((age-460)/140)),1,false,hero.library));
        }
        if(library&&!entries.isEmpty()) {
            var entry=entries.get(selected); double ty=(safeHeight>0?safeY+safeHeight:y+height)-42;
            FontManager.pf25bold.drawCenteredString(FontManager.pf25bold.trim(entry.title(),width-40),cardCenterX(),ty,AppTheme.foreground());
            String subtitle=entry.badge()+(entry.subtitle().isBlank()?"":" · "+entry.subtitle());
            FontManager.pf14.drawCenteredString(FontManager.pf14.trim(subtitle,width-40),cardCenterX(),ty+19,AppTheme.secondary());
        }
    }
    private Painted shift(Painted p,double dx,double dy,double scale,float alpha) {
        var b=p.box;
        return new Painted(p.id,p.entry,new FoliaGeometry.Box(b.index(),b.centerX()+dx-b.width()*scale/2,b.centerY()+dy-b.height()*scale/2,b.width()*scale,b.height()*scale),p.alpha*alpha,p.hover,p.wall,p.library);
    }
    private void paint(Painted p) {
        renderer.draw(p.entry,p.box,p.alpha,p.hover,keyboardFocus&&p.id.equals(focusedId),p.wall,p.library,p.id.equals(expanded),8*wallScale);
        if(p.alpha>.05) painted.add(p);
    }
    private Painted focusedPaint() {
        for(int i=painted.size()-1;i>=0;i--) if(painted.get(i).id.equals(focusedId)) return painted.get(i);
        return null;
    }
    public boolean click(double mx,double my,int button) {
        if(button!=0||!inside(mx,my)) return false;
        if(preferences.isFullscreen()) { int hit=flow.hitIndex(mx,my); if(hit>=0) { if(hit==selected) activate(); else select(hit); } return true; }
        var hit=hit(mx,my); pressedId=hit==null?null:hit.id; dragging=true; moved=false; keyboardFocus=false;
        pressX=mx; pressY=my; pressCameraX=targetX; pressCameraY=targetY; return true;
    }
    public void release(double mx,double my,int button) {
        if(button!=0||!dragging) return;
        dragging=false;
        var p=hit(mx,my);
        if(!moved&&Math.hypot(mx-pressX,my-pressY)<=4&&p!=null&&p.id.equals(pressedId)) {
            int old=selected; selected=p.box.index(); focusedId=p.id; remember();
            if(preferences.isWall()) {
                if(p.entry.collection()) activate();
                else if(p.id.equals(expanded)) activate();
                else expand((FoliaWall.Address)p.id);
            } else if(old==selected) activate(); else focusCamera();
        } else if(moved&&!preferences.isWall()&&content.isCollectionLibrary()&&!geometry.isEmpty()) {
            select((int)Math.round(targetX/(geometry.getFirst().width()+24)));
        }
        moved=false;
    }
    private Painted hit(double mx,double my) {
        if(!inside(mx,my)) return null;
        for(int i=painted.size()-1;i>=0;i--) if(painted.get(i).box.contains(mx,my)) {
            var p=painted.get(i); int index=p.box.index();
            if(index>=0&&index<entries.size()&&p.entry.key().equals(entries.get(index).key())) return p;
        }
        return null;
    }
    private boolean inside(double mx,double my) {
        // Drawing bleeds under the shell, but interactive targets stay in its safe area.
        if(safeWidth>0&&safeHeight>0) return mx>=safeX&&mx<safeX+safeWidth&&my>=safeY&&my<safeY+safeHeight;
        return mx>=x&&mx<x+width&&my>=y&&my<y+height;
    }
    private void select(int index) {
        selected=Math.clamp(index,0,Math.max(0,entries.size()-1)); keyboardFocus=true; focusCamera(); remember();
    }
    private void remember() { if(!entries.isEmpty()&&preferences.rememberPosition().getValue()) preferences.remember(content.key(),entries.get(selected).key()); }
    private void expand(FoliaWall.Address address) {
        long now=System.nanoTime()/1_000_000;
        Map<FoliaWall.Address,FoliaGeometry.Box> old=new HashMap<>();
        for(var tile:FoliaWall.visible(entries.size(),windows(),wallScale,cameraX,cameraY,width,height)) {
            var a=tile.address(); var b=tile.box();
            if(expanded!=null&&a.column()==expanded.column()&&a.row()==expanded.row()) b=FoliaWall.box(a,entries.size(),windows(),wallScale,expanded.slot());
            if(reflowFrom.containsKey(a)) b=mix(reflowFrom.get(a),b,reflow(now-reflowAt));
            old.put(a,b);
        }
        reflowFrom=old; reflowAt=now; expanded=address;
        if(address!=null) {
            var box=FoliaWall.box(address,entries.size(),windows(),wallScale,address.slot());
            targetX=box.centerX(); targetY=box.centerY();
        }
    }
    private void activate() {
        if(entries.isEmpty()) return;
        opening=entries.get(selected).collection();
        entries.get(selected).activate().run();
    }
    public boolean key(char character,int key) {
        if(KeyboardUtils.isKeyComboCtrl(key,Keyboard.KEY_F)||KeyboardUtils.isKeyComboCtrl(key,Keyboard.KEY_G)) return false;
        switch(key) {
            case Keyboard.KEY_LEFT,Keyboard.KEY_UP -> select(selected-1);
            case Keyboard.KEY_RIGHT,Keyboard.KEY_DOWN -> select(selected+1);
            case Keyboard.KEY_HOME -> select(0);
            case Keyboard.KEY_END -> select(entries.size()-1);
            case Keyboard.KEY_PRIOR -> select(selected-10);
            case Keyboard.KEY_NEXT -> select(selected+10);
            case Keyboard.KEY_RETURN,Keyboard.KEY_NUMPADENTER -> {
                if(preferences.isWall()&&!content.isCollectionLibrary()&&focusedId instanceof FoliaWall.Address address&&!address.equals(expanded)) expand(address);
                else activate();
            }
            case Keyboard.KEY_ESCAPE -> { if(expanded!=null) expand(null); else NCMScreen.getInstance().navigateBack(); }
            default -> { return false; }
        }
        return true;
    }
}
