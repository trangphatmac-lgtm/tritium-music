package tritium.minecraft.runtime.hook;

import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.runtime.movement.SprintModule;
import tritium.minecraft.runtime.movement.StasisModule;

/** Session-owned movement modules; always ticks the short Stasis disable cooldown. */
public final class MovementRuntime implements AutoCloseable {
    private final MovementAccess game;
    private final SprintModule sprint;
    private final StasisModule stasis;
    private HudConfig config;
    private Object world, player;
    private boolean sprintOn, stasisOn;
    public MovementRuntime(Object minecraft) { this(new MovementAccess(minecraft)); }
    MovementRuntime(MovementAccess game) { this.game=game;sprint=new SprintModule(game);stasis=new StasisModule(game); }
    public void configure(HudConfig config) {
        this.config=config;
        Object nextWorld=game.world(),nextPlayer=game.player();
        if (world!=nextWorld || player!=nextPlayer) {
            if (sprintOn) sprint.disable();
            sprintOn=stasisOn=false;stasis.disable(false);world=nextWorld;player=nextPlayer;
        }
        stasis.settings(config.find("stasis").movement);
        boolean active=world!=null && player!=null;
        boolean s=active && config.hidden && config.find("sprint").enabled;
        boolean t=active && config.hidden && config.find("stasis").enabled;
        if (sprintOn && !s) sprint.disable();
        if (stasisOn && !t) stasis.disable(true);
        if (!stasisOn && t) stasis.enable();
        sprintOn=s;stasisOn=t;
    }
    private boolean currentPlayer() {
        if (config != null && (world!=game.world() || player!=game.player())) configure(config);
        return world!=null && player!=null;
    }
    public float visibleYaw() { return currentPlayer() ? game.yaw() : 0; }
    public void preUpdate() { if (currentPlayer() && sprintOn) sprint.preUpdate(); }
    public void preMotion(float visibleYaw) { if (currentPlayer() && sprintOn) sprint.preMotion(config.find("sprint").movement,visibleYaw); }
    public void tickStart() { if (currentPlayer()) stasis.tickStart(); }
    public void tickEnd() { if (currentPlayer()) stasis.tickEnd(); }
    public void postInput() { if (currentPlayer()) stasis.postInput(); }
    public void afterPlayerUpdate() { if (currentPlayer()) stasis.afterPlayerUpdate(); }
    public boolean receive(Object packet) { return currentPlayer() && stasisOn && stasis.receive(packet); }
    public boolean send(Object manager,Object packet) { return stasis.send(manager,packet); }
    public boolean frozen() { return stasisOn && stasis.frozen(); }
    @Override public void close() {
        if (sprintOn) sprint.disable();sprintOn=stasisOn=false;
        stasis.disable(world==game.world() && player!=null && player==game.player());
    }
}
