import java.nio.file.*;import java.util.*;
public final class RootNavigationClassProof {
 public static void main(String[]args)throws Exception {
  var expected=Path.of(args[1]).toUri().toURL();int count=0;
  for(String name:Files.readAllLines(Path.of(args[0]))) {
   Class<?> type=Class.forName(name,false,RootNavigationClassProof.class.getClassLoader());
   if(!expected.equals(type.getProtectionDomain().getCodeSource().getLocation()))throw new AssertionError(name);
   type.getDeclaredMethods();type.getDeclaredConstructors();count++;
  }
  System.out.println("Loaded "+count+" new Root/navigation class definitions from actual46; no initialization or UI actions.");
 }
}
