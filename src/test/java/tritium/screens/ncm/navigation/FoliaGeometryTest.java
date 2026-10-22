package tritium.screens.ncm.navigation;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class FoliaGeometryTest {
    @Test void hexSpiralKeepsEveryTrackReachableAndUsesFoliaFalloff() {
        var cards=FoliaGeometry.cards(10000,140,210); var centers=new HashSet<String>();
        for(int i=0;i<cards.size();i++) { var c=cards.get(i); assertEquals(i,c.index()); assertTrue(centers.add(c.centerX()+":"+c.centerY())); }
        assertEquals(0,cards.getFirst().centerX()); assertEquals(0,cards.getFirst().centerY());
        assertEquals(1.1,FoliaGeometry.falloff(0,500,.45,.4).scale());
        assertEquals(.45,FoliaGeometry.falloff(600,500,.45,.4).scale(),1e-9);
        assertEquals(.4,FoliaGeometry.falloff(600,500,.45,.4).opacity(),1e-6);
    }
    @Test void sparseWallCoversTheViewportAtEveryCameraPosition() {
        for(int count:new int[]{1,2,5,20,5000}) for(double camera:new double[]{0,-4000,67890}) {
            var tiles=FoliaWall.visible(count,0,.38,camera,camera,900,600);
            assertTrue(tiles.size()<400);
            assertTrue(tiles.stream().allMatch(t->t.box().index()>=0&&t.box().index()<count));
            // Every grid cell has content; only the original 8-unit seams are left uncovered.
            double pitch=136*.38;
            int left=(int)Math.floor((camera-450)/pitch),top=(int)Math.floor((camera-300)/pitch);
            for(int row=top;row<top+13;row++) for(int col=left;col<left+19;col++) {
                double x=(col+.5)*pitch,y=(row+.5)*pitch;
                assertEquals(1,tiles.stream().filter(t->t.box().contains(x,y)).count(),"A sparse library must repeat with neither holes nor overlap");
            }
        }
    }
    @Test void everyOriginalItemCanBeFocusedIncludingNegativeWorldCopies() {
        for(int windows=0;windows<=6;windows++) for(int count:new int[]{1,5,49,5000}) {
            for(int index:new int[]{0,count/2,count-1}) {
                var tile=FoliaWall.nearest(index,count,windows,.38,-800,-900);
                assertNotNull(tile); assertEquals(index,tile.box().index());
                var same=FoliaWall.box(tile.address(),count,windows,.38,-1);
                assertEquals(tile.box(),same);
            }
        }
        assertTrue(FoliaWall.visible(0,0,.38,0,0,900,600).isEmpty());
    }
    @Test void everyExpansionExactlyRetilesItsBlockAndPreservesIdentities() {
        for(int column=-2;column<3;column++) for(int row=-2;row<3;row++) for(int expanded=0;expanded<12;expanded++) {
            var boxes=new java.util.ArrayList<FoliaGeometry.Box>();
            for(int slot=0;slot<12;slot++) boxes.add(FoliaWall.box(new FoliaWall.Address(column,row,slot),49,0,1,expanded));
            assertEquals(808,boxes.get(expanded).width()); assertEquals(808,boxes.get(expanded).height());
            for(int cx=0;cx<12;cx++) for(int cy=0;cy<8;cy++) {
                double x=(column*12+cx+.5)*136,y=(row*8+cy+.5)*136;
                assertEquals(1,boxes.stream().filter(b->b.contains(x,y)).count());
            }
        }
    }
    @Test void springAndFlightReachTheirEndpointsWithoutAbruptSteps() {
        assertEquals(0,FoliaMotion.flight(0)); assertEquals(1,FoliaMotion.flight(460));
        double previous=0;
        for(int ms=0;ms<=800;ms+=10) { double value=FoliaMotion.reflow(ms); assertTrue(value>=previous-1e-6&&value<=1.001); previous=value; }
        assertTrue(FoliaMotion.flight(200)>.7); assertTrue(FoliaMotion.reflow(150)>.6);
    }
    @Test void wallSeamsStayUniformAndPaperNeverCrossesIntoAnotherCover() {
        for(double scale:new double[]{.26*.6,.32,.38,.38*2.5}) for(double pixels:new double[]{1,2,3.333333,4})
            for(double camera:new double[]{0,.137,173.29}) {
                double gap=8*scale, expectedGap=Math.round(gap*pixels)/pixels;
                var tiles=FoliaWall.visible(5,0,scale,camera,camera,450,320);
                var boxes=tiles.stream()
                        .map(t->{ var b=t.box(); return FoliaWallSeams.snap(new FoliaGeometry.Box(b.index(),b.x()-camera,b.y()-camera,b.width(),b.height()),gap,pixels); }).toList();
                for(var tile:tiles) {
                    var b=tile.box();
                    var face=FoliaWallSeams.snap(new FoliaGeometry.Box(b.index(),b.x()-camera,b.y()-camera,b.width(),b.height()),gap,pixels);
                    var neighbour=FoliaWallSeams.snap(new FoliaGeometry.Box(0,b.x()+b.width()+gap-camera,b.y()+b.height()+gap-camera,128*scale,128*scale),gap,pixels);
                    assertEquals(expectedGap,neighbour.x()-face.x()-face.width(),1e-9);
                    assertEquals(expectedGap,neighbour.y()-face.y()-face.height(),1e-9);
                }
                for(var b:boxes) {
                    for(double hover:new double[]{0,.2,.7,1}) for(var edge:FoliaWallSeams.paperEdges(b,gap,pixels,hover)) {
                        assertEquals(Math.rint(edge.x()*pixels),edge.x()*pixels,1e-8);
                        assertEquals(Math.rint(edge.y()*pixels),edge.y()*pixels,1e-8);
                        assertTrue(edge.x()+edge.width()<=b.x()+b.width()+expectedGap+1e-8);
                        assertTrue(edge.y()+edge.height()<=b.y()+b.height()+expectedGap+1e-8);
                        assertTrue(boxes.stream().noneMatch(other->overlap(edge,other)),"Paper edges must remain inside gutters at every zoom/DPI");
                    }
                    assertTrue(FoliaWallSeams.paperEdges(b,gap,pixels,1).isEmpty());
                }
            }
    }
    private static boolean overlap(FoliaGeometry.Box a,FoliaGeometry.Box b) {
        return Math.min(a.x()+a.width(),b.x()+b.width())-Math.max(a.x(),b.x())>1e-8
                &&Math.min(a.y()+a.height(),b.y()+b.height())-Math.max(a.y(),b.y())>1e-8;
    }
}
