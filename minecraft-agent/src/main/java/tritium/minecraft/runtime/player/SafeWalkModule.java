package tritium.minecraft.runtime.player;
import tritium.minecraft.runtime.hook.PlayerAccess;

/** Edge guard and OpenB4's key/state sneak modes, including physical-key takeover rules. */
public final class SafeWalkModule extends PlayerModule {
    private static final double[] TOLERANCE={.05,.15,.25,.3};
    private boolean sneaking,scaffoldActive;
    private long sneakStart;
    private final ScaffoldSupport scaffold;
    public SafeWalkModule(PlayerAccess game,ScaffoldSupport scaffold){super(game);this.scaffold=scaffold;}
    public int mode(){return (int)number("Mode");}
    public boolean scaffolding(){return scaffoldActive;}
    private boolean conditions(boolean ground,boolean ignoreSneak){
        if(ground&&!game.ground())return false;
        if(number("Minimum pitch")!=19&&game.pitch()<number("Minimum pitch"))return false;
        if(on("Only while right clicking")&&!game.mouse(1))return false;
        if(!ignoreSneak&&on("Only while sneaking")&&!game.physicalKey("Sneak"))return false;
        if(on("Disable on forward")&&game.physicalKey("Forward"))return false;
        return !on("Blocks only")||game.scaffoldBlock();
    }
    public boolean stopOnEdge(){return mode()==0&&conditions(true,false);}
    public boolean mouse(boolean down,int button){return down&&button==1&&mode()==3&&on("Only while right clicking")&&conditions(false,false);}
    public void screenChanged(Object screen){if(mode()!=0&&screen==null)sneaking=game.sneaking();}
    public void postInput(){if(mode()==2&&sneaking)game.sneakState(true);}
    public boolean atEdge(){
        double x=game.x(),y=game.boxMinY()-.01,z=game.z(),mx=game.motionX(),mz=game.motionZ();
        float forward=((game.keyDown("Forward")?1:0)-(game.keyDown("Back")?1:0))*.98f;
        float strafe=((game.keyDown("Left")?1:0)-(game.keyDown("Right")?1:0))*.98f,square=forward*forward+strafe*strafe;
        if(square>=1.0E-4f){float speed=game.moveSpeed(),len=game.sqrt(square);speed*=.9999998f;if(len>=1)speed/=len;
            float yaw=game.yaw()*((float)Math.PI/180),sin=game.sin(yaw),cos=game.cos(yaw);mx+=strafe*speed*cos-forward*speed*sin;mz+=forward*speed*cos+strafe*speed*sin;}
        double nx=x+mx,nz=z+mz,t=TOLERANCE[(int)number("Edge tolerance")];int by=(int)Math.floor(y),x1=(int)Math.floor(nx-t),z1=(int)Math.floor(nz-t);
        if(game.solid(x1,by,z1))return false;int z2=(int)Math.floor(nz+t);if(z1!=z2&&game.solid(x1,by,z2))return false;
        int x2=(int)Math.floor(nx+t);if(x1!=x2){if(game.solid(x2,by,z1))return false;if(z1!=z2&&game.solid(x2,by,z2))return false;}return true;
    }
    private boolean unsneakReady(){return number("Unsneak delay")==0||game.now()-sneakStart>number("Unsneak delay")+40;}
    @Override public void preUpdate(){
        if(on("Only while sneaking")&&!(game.physicalKey("Sneak")&&conditions(false,true))){sneaking=false;game.key("Sneak",game.physicalKey("Sneak"));}
        else if(on("Only while sneaking"))game.key("Sneak",mode()==1||mode()==3?sneaking:false);
        if(mode()==0)return;
        if(mode()==3){
            if(conditions(false,false)){if(!scaffoldActive){scaffoldActive=true;scaffold.enable();}
                if(game.ground()&&!game.physicalKey("Jump")&&atEdge())setSneak(true);else if(sneaking&&unsneakReady())setSneak(false);
            }else if(scaffoldActive){scaffoldActive=false;scaffold.disable(true);if(sneaking)setSneak(false);}return;
        }
        if(!conditions(true,false)){if(sneaking)setSneak(false);return;}
        if(atEdge())setSneak(true);else if(sneaking&&unsneakReady())setSneak(false);
    }
    private void setSneak(boolean value){
        if(sneaking==value)return;if(!value&&!on("Only while sneaking")&&game.physicalKey("Sneak"))return;
        sneaking=value;if(value)sneakStart=game.now();if(mode()==1||mode()==3)game.key("Sneak",value);
    }
    @Override public void disable(boolean samePlayer){
        if(scaffoldActive){scaffoldActive=false;scaffold.disable(samePlayer);}
        if(samePlayer&&(sneaking||on("Only while sneaking")))game.restoreKey("Sneak");sneaking=false;sneakStart=0;
    }
}
