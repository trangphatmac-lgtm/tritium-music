package tritium.minecraft.runtime.player;
import tritium.minecraft.runtime.hook.PlayerAccess;
public final class FastPlaceModule extends PlayerModule {
    private final AutoSwapModule swap;
    private boolean active,lastHeldBlocks;
    private int lastSlot=-1;
    private long fadeInStart,fadeOutStart,holdStart;
    private String countText;
    public FastPlaceModule(PlayerAccess game,AutoSwapModule swap){super(game);this.swap=swap;}
    public boolean autoSwap(){return on("Auto swap blocks");}
    public boolean send(Object packet){return game.airBlockPlacement(packet)&&game.random()>.4;}
    private void active(boolean value){if(value&&!active){active=true;fadeInStart=game.now();}else if(!value&&active){active=false;fadeOutStart=game.now();}}
    public void render(boolean scaffoldActive){
        if(!on("Show block count")||countText==null||scaffoldActive)return;
        long elapsed=game.now()-(active?fadeInStart:fadeOutStart);int alpha=active?(elapsed<125?(int)(elapsed/125f*240):240):elapsed<125?(int)(240-elapsed/125f*240):0;
        if(!active&&alpha<=0)countText=null;if(alpha>0)game.blockCount(countText,alpha);
    }
    public static String countText(int count){return count<=5?"§c"+count+"§f blocks":count<=15?"§6"+count+"§f blocks":count<=25?"§e"+count+"§f blocks":count+" blocks";}
    @Override public void update(){
        if(on("Pitch check")&&game.pitch()<65){active(false);return;}
        int slot=game.slot();boolean blocks=game.scaffoldBlock();
        if(autoSwap()&&(!blocks||game.count(game.held())<=swap.switchAt())&&lastHeldBlocks&&slot==lastSlot){swap.swap();blocks=game.scaffoldBlock();}
        lastSlot=slot;lastHeldBlocks=blocks;
        if(blocks){if(on("Show block count"))countText=countText(autoSwap()?swap.countBlocks():game.count(game.held()));}
        else {if(on("Blocks only")&&!(on("Allow ladders")&&game.ladder(game.held()))){active(false);return;}countText=null;}
        if(number("Minimum hold time")!=0){long now=game.now();if(!game.mouse(1)){holdStart=0;active(false);return;}if(holdStart==0)holdStart=now;
            if(now-holdStart<number("Minimum hold time")-10){active(false);return;}}
        active(true);int delay=(int)number("Delay");if(delay>1){if(game.rightClickDelay()==4)game.rightClickDelay(delay);}else game.rightClickDelay(1);
    }
    @Override public void disable(boolean samePlayer){active(false);lastHeldBlocks=false;holdStart=0;if(!samePlayer)countText=null;}
}
