// SPDX-License-Identifier: AGPL-3.0-only
// Port of Folia wall/startTile, blockTemplates and bravaisItemSlots; see Folia-NOTICE.txt.
package tritium.screens.ncm.navigation;

import java.util.ArrayList;
import java.util.List;

/** Infinite, viewport-culled Bravais field. Repetition belongs to slots, never the source queue. */
public final class FoliaWall {
    private FoliaWall() { }
    public record Address(int column, int row, int slot) { }
    public record Tile(Address address, FoliaGeometry.Box box) { }
    public record Period(int columns, int rows, int capacity) { }
    public static Period period(int count, int windows) {
        int capacity = 12 - Math.clamp(windows, 0, 6);
        int blocks = Math.max(1, (count + capacity - 1) / capacity);
        int columns = blocks == 1 ? 1 : Math.max(1, (int)Math.round(Math.sqrt(2.2 * blocks * 8 / 12)));
        return new Period(columns, (blocks + columns - 1) / columns, capacity);
    }
    public static List<Tile> visible(int count, int windows, double scale, double cx, double cy, double width, double height) {
        if (count <= 0) return List.of();
        List<Tile> result = new ArrayList<>();
        double bw=12*136*scale, bh=8*136*scale, overscan=80;
        int left=(int)Math.floor((cx-width/2-overscan)/bw), right=(int)Math.floor((cx+width/2+overscan)/bw);
        int top=(int)Math.floor((cy-height/2-overscan)/bh), bottom=(int)Math.floor((cy+height/2+overscan)/bh);
        for (int row=top; row<=bottom; row++) for (int col=left; col<=right; col++) {
            for (int slot=0;slot<12;slot++) {
                Address address=new Address(col,row,slot);
                result.add(new Tile(address,box(address,count,windows,scale,-1)));
            }
        }
        return result;
    }
    public static FoliaGeometry.Box box(Address a,int count,int windows,double scale,int expandedSlot) {
        int variant=Math.floorMod(a.column+a.row*2,4);
        int[] t=expandedSlot<0?FoliaGeometry.TEMPLATES[variant][a.slot]:FoliaWallReflows.DATA[variant][expandedSlot][a.slot];
        int hash=a.column*0x9e3779b1 ^ a.row*0x85ebca6b;
        hash=(hash^(hash>>>15))*0x2c1b3c6d; hash^=hash>>>12;
        int reflection=hash&3;
        int tx=(reflection==1||reflection==3)?12-t[0]-t[2]:t[0], ty=reflection>=2?8-t[1]-t[3]:t[1];
        boolean[] reserved=FoliaGeometry.reserved(a.column,a.row,windows);
        int index=-1;
        if (count>0 && !reserved[a.slot]) {
            int within=0;
            for(int s=0;s<a.slot;s++) if(!reserved[s]) within++;
            Period period=period(count,windows);
            int block=Math.floorMod(a.row,period.rows)*period.columns+Math.floorMod(a.column,period.columns);
            index=(block*period.capacity+within)%count;
        }
        return new FoliaGeometry.Box(index,(a.column*12+tx)*136*scale,(a.row*8+ty)*136*scale,
                (t[2]*136-8)*scale,(t[3]*136-8)*scale);
    }
    /** Constant-time reverse lookup, including far-off tracks and negative-world copies. */
    public static Tile nearest(int index,int count,int windows,double scale,double cx,double cy) {
        if(count<=0) return null;
        Period period=period(count,windows);
        Tile best=null; double distance=Double.POSITIVE_INFINITY;
        int total=period.columns*period.rows*period.capacity;
        for(int p=Math.floorMod(index,count);p<total;p+=count) {
            int block=p/period.capacity, within=p%period.capacity;
            int col=block%period.columns,row=block/period.columns;
            int cycleX=(int)Math.round((cx/(12*136*scale)-col-.5)/period.columns);
            int cycleY=(int)Math.round((cy/(8*136*scale)-row-.5)/period.rows);
            for(int dy=-1;dy<=1;dy++) for(int dx=-1;dx<=1;dx++) {
                int ac=col+(cycleX+dx)*period.columns, ar=row+(cycleY+dy)*period.rows;
                boolean[] reserved=FoliaGeometry.reserved(ac,ar,windows); int ordinal=0;
                for(int s=0;s<12;s++) if(!reserved[s] && ordinal++==within) {
                    Address address=new Address(ac,ar,s); var b=box(address,count,windows,scale,-1);
                    double d=Math.pow(b.centerX()-cx,2)+Math.pow(b.centerY()-cy,2);
                    if(d<distance) { distance=d; best=new Tile(address,b); }
                    break;
                }
            }
        }
        return best;
    }
}
