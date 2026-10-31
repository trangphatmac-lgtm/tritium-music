package tritium.minecraft.runtime.hook;

import java.util.*;
import tritium.minecraft.protocol.HudConfig;
import tritium.minecraft.runtime.player.*;

/** Coordinates OpenB4 player modules and their shared slot, rotation and Scaffold dependency state. */
public final class PlayerRuntime implements AutoCloseable {
    private final PlayerAccess game;
    private final Map<String,PlayerModule> modules=new LinkedHashMap<String,PlayerModule>();
    private final Set<String> active=Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String,Boolean>());
    private final AutoJumpModule autoJump;
    private final AutoLadderModule autoLadder;
    private final AutoSwapModule autoSwap;
    private final AutoToolModule autoTool;
    private final FastPlaceModule fastPlace;
    private final SafeWalkModule safeWalk;
    private final WaterBucketModule waterBucket;
    private final ScaffoldSupport scaffold;
    private final BridgeAssistModule bridgeAssist;
    private volatile HudConfig config;
    private boolean movementFix;
    private Object player,world,screen;
    private boolean spoofing,spoofFist,resetAnimation;
    private int stopSpoofTick,extraSpoofTicks;
    public PlayerRuntime(Object minecraft){this(new PlayerAccess(minecraft));}
    public PlayerRuntime(PlayerAccess game){
        this.game=game;autoJump=new AutoJumpModule(game);autoSwap=new AutoSwapModule(game);scaffold=new ScaffoldSupport(game,autoSwap);
        autoLadder=new AutoLadderModule(game);autoTool=new AutoToolModule(game);fastPlace=new FastPlaceModule(game,autoSwap);
        safeWalk=new SafeWalkModule(game,scaffold);waterBucket=new WaterBucketModule(game);bridgeAssist=new BridgeAssistModule(game);
        modules.put("autoJump",autoJump);modules.put("autoLadder",autoLadder);modules.put("autoSwap",autoSwap);modules.put("autoTool",autoTool);
        modules.put("delayRemove",new DelayRemoveModule(game));modules.put("fastMine",new FastMineModule(game));modules.put("fastPlace",fastPlace);
        modules.put("safeWalk",safeWalk);modules.put("waterBucket",waterBucket);modules.put("bridgeAssist",bridgeAssist);
    }
    public void configure(HudConfig next){
        Object nextPlayer=game.player(),nextWorld=game.world();boolean same=player!=null&&player==nextPlayer&&world==nextWorld;
        if(!same){reset(false);player=nextPlayer;world=nextWorld;}
        config=next;boolean available=next.hidden&&player!=null&&world!=null&&game.windowActive();
        for(Map.Entry<String,PlayerModule> e:modules.entrySet()){
            String id=e.getKey();PlayerModule module=e.getValue();boolean was=active.contains(id),enable=available&&next.find(id).enabled;
            // Changing SafeWalk mode releases the previous mode's owned key before applying new options.
            if(was&&module==safeWalk&&safeWalk.mode()!=(int)next.find(id).openB4.number("Mode")){module.disable(same);was=false;active.remove(id);}
            module.settings(next.find(id).openB4);
            if(was&&!enable){active.remove(id);module.disable(same);}else if(!was&&enable){active.add(id);module.enable();}
        }
        Object nextScreen=game.screen();if(nextScreen!=screen){screen=nextScreen;screenChanged(screen);}
    }
    private boolean current(){if(config!=null&&(player!=game.player()||world!=game.world()))configure(config);return player!=null&&world!=null;}
    public boolean enabled(String id){return active.contains(id);}
    public boolean scaffolding(){return scaffold.active();}
    public void preUpdate(){if(!current())return;
        // OpenB4's manager runs Scaffold before SafeWalk; a newly requested Scaffold starts next tick.
        scaffold.preUpdate();if(enabled("safeWalk"))safeWalk.preUpdate();
        autoLadder.scaffolding(scaffold.active());autoTool.scaffolding(scaffold.active());
        autoSwap.handled(scaffold.active()||enabled("fastPlace")&&fastPlace.autoSwap());
        if(enabled("autoSwap"))autoSwap.preUpdate();
        if(enabled("autoLadder"))autoLadder.preUpdate();if(enabled("waterBucket"))waterBucket.preUpdate();
    }
    public void tickEnd(){if(!current())return;for(String id:new String[]{"autoJump","autoTool","delayRemove","fastMine","fastPlace"})if(enabled(id))modules.get(id).update();}
    public void preInput(Object input){if(current()&&enabled("bridgeAssist")&&input==game.input())bridgeAssist.preInput(input);}
    public boolean rightClick(){return current()&&enabled("bridgeAssist")&&bridgeAssist.rightClick();}
    public boolean slotScroll(Object inventory,int wheelDelta){return current()&&inventory==game.inventory()&&enabled("bridgeAssist")&&bridgeAssist.slotScroll();}
    public float[] clientRotations(float baseYaw,float basePitch){return current()&&enabled("bridgeAssist")?bridgeAssist.clientRotations(baseYaw,basePitch):null;}
    public void postInputEarly(){if(current())scaffold.postInput();}
    public void postInput(){if(current()&&enabled("safeWalk"))safeWalk.postInput();}
    public boolean mouse(boolean down,int button){return current()&&(scaffold.mouse(down)||enabled("safeWalk")&&safeWalk.mouse(down,button));}
    /** Must observe sends before other modules may cancel them (PacketUtils.track semantics). */
    public void observeSend(Object packet){scaffold.observeSend(packet);if(enabled("bridgeAssist"))bridgeAssist.observeSend(packet);}
    public boolean send(Object packet){return enabled("fastPlace")&&fastPlace.send(packet);}
    public void screenChanged(Object screen){if(current()&&enabled("safeWalk"))safeWalk.screenChanged(screen);}
    public boolean stopsOnEdge(Object entity,boolean vanilla){return current()&&entity==player&&enabled("safeWalk")&&game.ground()&&game.airBelow()&&safeWalk.stopOnEdge()||vanilla;}
    public float[] rotations(float lastYaw,float lastPitch){movementFix=false;if(!current())return null;float[] r=scaffold.rotations(lastYaw,lastPitch);if(r!=null){movementFix=true;return r;}
        r=autoLadder.rotations(lastYaw,lastPitch);if(r!=null){movementFix=true;return r;}return enabled("waterBucket")?waterBucket.rotations():null;}
    public boolean movementFix(){return movementFix;}
    /** Default movement fix permits sprint, except the source's one-tick deferred-jump handoff. */
    public boolean blocksSprint(){return scaffold.blocksSprint();}
    public int spoofedSlot(){return autoTool.previousSlot();}
    public Object itemToRender(Object original){
        if(!current())return original;
        int slot=autoTool.previousSlot();
        if(slot!=-1&&config.find("autoTool").openB4.on("Spoof item")){
            Object item=game.stack(slot);resetAnimation=true;spoofing=true;spoofFist=item==null;return item;
        }
        if(spoofing){original=game.held();stopSpoofTick=game.ticks();extraSpoofTicks=5;}
        if(extraSpoofTicks>0){resetAnimation=true;if(game.ticks()!=stopSpoofTick){++stopSpoofTick;--extraSpoofTicks;}}
        spoofFist=false;spoofing=false;return original;
    }
    public float animationProgress(float original){if(resetAnimation){resetAnimation=false;return 0;}return original;}
    public boolean spoofingItemAsFist(){return spoofFist;}
    public void renderHud(){if(!current()||config==null||!config.hidden||!game.canRenderOverlay())return;scaffold.renderHud();fastPlace.render(safeWalk.scaffolding());}
    public void renderWorld(float partialTicks){if(current()&&config!=null&&config.hidden)scaffold.renderWorld();}
    private void reset(boolean samePlayer){for(PlayerModule module:modules.values())module.disable(samePlayer);active.clear();scaffold.disable(samePlayer);spoofing=spoofFist=resetAnimation=false;extraSpoofTicks=0;movementFix=false;}
    @Override public void close(){reset(player!=null&&player==game.player()&&world==game.world());}
}
