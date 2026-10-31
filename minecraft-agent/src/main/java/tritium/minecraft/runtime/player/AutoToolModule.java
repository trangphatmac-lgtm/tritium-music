package tritium.minecraft.runtime.player;
import tritium.minecraft.runtime.hook.PlayerAccess;

public final class AutoToolModule extends PlayerModule {
    private int previousSlot=-1;
    private boolean swapped,scaffolding;
    private long hoverTicks;
    public AutoToolModule(PlayerAccess game) {super(game);}
    public int previousSlot() {return previousSlot;}
    public void scaffolding(boolean value) {scaffolding=value;}
    @Override public void update() {
        if(!game.focused()||scaffolding||on("Disable while holding blocks")&&game.scaffoldBlock()
                ||on("Require mouse down")&&!game.mouse(0)||on("Disable while right click")&&game.mouse(1)) {reset();return;}
        boolean tools=on("Enable when holding tools"),sneak=on("Enable when sneaking");
        if(!(!tools&&!sneak||sneak&&game.sneaking()||tools&&game.tool())) {reset();return;}
        Object hit=game.hit();if(!game.blockHit(hit)){hoverTicks=0;reset();return;}
        if(number("Hover delay")!=0&&++hoverTicks<number("Hover delay"))return;
        Object block=game.block(game.hitPos(hit));
        if(on("Disable on interactable")&&game.interactable(block)){reset();return;}
        if(!swapped&&on("Swap to previous slot"))previousSlot=game.slot();
        swapped=true;game.slot(game.bestTool(block));
    }
    private void reset(){if(previousSlot!=-1){game.slot(previousSlot);previousSlot=-1;}swapped=false;}
    @Override public void disable(boolean samePlayer){hoverTicks=0;if(samePlayer)reset();else {previousSlot=-1;swapped=false;}}
}
