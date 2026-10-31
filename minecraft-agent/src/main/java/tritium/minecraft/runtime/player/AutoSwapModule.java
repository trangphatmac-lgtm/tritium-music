package tritium.minecraft.runtime.player;
import tritium.minecraft.runtime.hook.PlayerAccess;

/** Includes the standalone OpenB4 u20 trigger and the utility used by FastPlace/Scaffold. */
public final class AutoSwapModule extends PlayerModule {
    public boolean swapped;
    private boolean lastHeldBlocks,handled;
    private int lastSlot=-1;
    public AutoSwapModule(PlayerAccess game){super(game);}
    public void handled(boolean value){handled=value;}
    @Override public void preUpdate(){
        if(game.screen()!=null)return;
        int slot=game.slot();boolean blocks=game.scaffoldBlock();
        if(!handled&&game.keyDown("UseItem")&&lastHeldBlocks&&slot==lastSlot&&(!blocks||game.count(game.held())<=number("Switch at"))){swap();blocks=game.scaffoldBlock();}
        lastSlot=game.slot();lastHeldBlocks=blocks;
    }
    public void swap(){
        swapped=false;if(game.screenOpen())return;
        Object held=game.held();boolean block=game.scaffoldBlock(held);
        if(!on("Swap to greater stack")&&block&&game.count(held)>number("Switch at"))return;
        game.clearEmptyStacks();int best=-1,slot=-1;
        for(int i=8;i>=0;i--){
            if(i==game.slot())continue;Object s=game.stack(i);
            if(s==null||!game.blockItem(s)||best>=game.count(s))continue;
            if(block&&(game.count(held)>=game.count(s)||on("Only same type")&&game.item(held)!=game.item(s)))continue;
            if(!game.scaffoldBlock(s))continue;best=game.count(s);slot=i;
        }
        if(slot!=-1&&game.slot()!=slot){game.slot(slot);swapped=true;}
    }
    public int countBlocks(){int count=0;for(int i=0;i<9;i++){Object s=game.stack(i);if(game.scaffoldBlock(s))count+=game.count(s);}return count;}
    public double switchAt(){return number("Switch at");}
    @Override public void disable(boolean samePlayer){lastHeldBlocks=false;lastSlot=-1;swapped=false;}
}
