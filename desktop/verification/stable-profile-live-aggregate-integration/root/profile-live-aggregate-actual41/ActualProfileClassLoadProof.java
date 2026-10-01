public final class ActualProfileClassLoadProof {
 public static void main(String[] args) throws Exception {
  java.net.URL expected=java.nio.file.Path.of(args[1]).toUri().toURL();int count=0;
  for(String name:java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[0]))) {
   Class<?> type=Class.forName(name,false,ActualProfileClassLoadProof.class.getClassLoader());
   if(!expected.equals(type.getProtectionDomain().getCodeSource().getLocation()))throw new AssertionError(name);
   count++;
  }
  if(count!=180)throw new AssertionError(count);
  System.out.println("PASS actual41/97 installed Profile classes="+count+" initialized=false overrides=0");
 }
}
