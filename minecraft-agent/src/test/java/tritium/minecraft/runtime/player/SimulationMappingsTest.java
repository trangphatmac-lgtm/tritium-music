package tritium.minecraft.runtime.player;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import tritium.minecraft.runtime.player.simulation.SimulationMappings;
import tritium.minecraft.runtime.player.simulation.SimulationWorld.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="minecraft.mappingClasspathFile",matches=".+")
class SimulationMappingsTest {
    @Test void completeSimulatorReflectionSurfaceExistsInRealMinecraftClasses() throws Exception {
        try(URLClassLoader loader=loader()){
            for(String[] member:SimulationMappings.MEMBERS){String names=SimulationMappings.name(member[1]);assertTrue(contains(loader,member[0],member[2].equals("field"),new HashSet<String>(Arrays.asList(names.split("/"))),new HashSet<String>()),member[0]+"."+names);}
        }
    }
    @Test void detachedMathAndBoundingBoxesMatchActualMinecraftImplementation() throws Exception {
        try(URLClassLoader loader=loader()){
            Class<?> math=loader.loadClass("net.minecraft.util.MathHelper"),bb=loader.loadClass("net.minecraft.util.AxisAlignedBB");Random random=new Random(71158);
            for(int i=0;i<100;i++){
                float angle=(random.nextFloat()-.5f)*10000;assertEquals(MathHelper.func_76126_a(angle),((Number)invoke(math,"func_76126_a",new Class[]{float.class},angle)).floatValue());assertEquals(MathHelper.func_76134_b(angle),((Number)invoke(math,"func_76134_b",new Class[]{float.class},angle)).floatValue());
                double x=random.nextDouble()*3,y=random.nextDouble()*3,z=random.nextDouble()*3;AxisAlignedBB a=new AxisAlignedBB(0,0,0,1,1,1),b=new AxisAlignedBB(x,y,z,x+.6,y+1.8,z+.6);
                Object ra=bb.getConstructor(double.class,double.class,double.class,double.class,double.class,double.class).newInstance(0.,0.,0.,1.,1.,1.);
                Object rb=bb.getConstructor(double.class,double.class,double.class,double.class,double.class,double.class).newInstance(x,y,z,x+.6,y+1.8,z+.6);
                double velocity=(random.nextDouble()-.5)*4;
                assertEquals(a.func_72316_a(b,velocity),((Number)invoke(ra,"func_72316_a",new Class[]{bb,double.class},rb,velocity)).doubleValue());
                assertEquals(a.func_72323_b(b,velocity),((Number)invoke(ra,"func_72323_b",new Class[]{bb,double.class},rb,velocity)).doubleValue());
                assertEquals(a.func_72322_c(b,velocity),((Number)invoke(ra,"func_72322_c",new Class[]{bb,double.class},rb,velocity)).doubleValue());
            }
        }
    }
    static Object invoke(Object o,String srg,Class<?>[] types,Object... args)throws Exception{Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();for(String name:SimulationMappings.name(srg).split("/"))try{return c.getMethod(name,types).invoke(o instanceof Class?null:o,args);}catch(NoSuchMethodException e){}throw new NoSuchMethodException(srg);}
    static URLClassLoader loader()throws Exception{List<URL> urls=new ArrayList<URL>();for(String p:Files.readAllLines(Paths.get(System.getProperty("minecraft.mappingClasspathFile"))))if(!p.trim().isEmpty())urls.add(Paths.get(p).toUri().toURL());return new URLClassLoader(urls.toArray(new URL[0]),SimulationMappingsTest.class.getClassLoader());}
    static boolean contains(ClassLoader loader,String owner,boolean field,Set<String> names,Set<String> seen)throws Exception{
        if(owner==null||!seen.add(owner))return false;try(InputStream in=loader.getResourceAsStream(owner+".class")){if(in==null)return false;ClassNode c=new ClassNode();new ClassReader(in).accept(c,ClassReader.SKIP_CODE);
            if(field){for(FieldNode f:c.fields)if(names.contains(f.name))return true;}else{for(MethodNode m:c.methods)if(names.contains(m.name))return true;}
            if(contains(loader,c.superName,field,names,seen))return true;for(String i:c.interfaces)if(contains(loader,i,field,names,seen))return true;return false;
        }
    }
}
