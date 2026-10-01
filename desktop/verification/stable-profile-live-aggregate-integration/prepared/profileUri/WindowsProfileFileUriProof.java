import java.nio.file.*;
public class WindowsProfileFileUriProof {
 public static void main(String[] args) {
  Path path=Path.of("C:\\Users\\TONYS\\Documents\\Profile wallpaper 空格\\cover.jpg").toAbsolutePath().normalize();
  String uri=path.toUri().toString();
  if(!uri.startsWith("file:///"))throw new AssertionError(uri);
  if(!Path.of(java.net.URI.create(uri)).equals(path))throw new AssertionError("drive roundtrip");
  if(!new java.io.File(java.nio.file.Paths.get(java.net.URI.create(uri)).toString()).toPath().equals(path))throw new AssertionError("original file-existence adapter");
  if(!uri.contains("%20"))throw new AssertionError("escaped spaces");
  System.out.println("PASS 4 native Windows file URI/path assertions; no file read/network/Root window");
 }
}