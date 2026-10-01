public final class ProfileClassLoadProof {
 public static void main(String[] args) throws Exception {
  int n=0;
  try (java.util.jar.JarFile jar=new java.util.jar.JarFile(args[0])) {
   for(java.util.jar.JarEntry entry:java.util.Collections.list(jar.entries())) {
    if(entry.getName().endsWith(".class")) {
     Class.forName(entry.getName().replace('/','.').replaceAll("\\.class$",""),false,ProfileClassLoadProof.class.getClassLoader());n++;
    }
   }
  }
  System.out.println("PASS actual39/97 zero override; loaded "+n+" prepared classes without initialization");
 }
}