package actual31.receipt
import java.io.File
import java.security.MessageDigest
fun main(args:Array<String>) {
  val fixture=Class.forName(args[0])
  fixture.getDeclaredMethod("main",Array<String>::class.java).invoke(null,arrayOf(args[1]))
  val names=args.drop(2)
  val rows=names.map {name ->
    val c=Class.forName(name)
    val bytes=requireNotNull(c.getResourceAsStream("/"+name.replace('.','/')+".class")).use {it.readBytes()}
    val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    "{\"class\":\"$name\",\"path\":\"${File(c.protectionDomain.codeSource.location.toURI()).absolutePath.replace("\\","/")}\",\"classSha256Bytes\":\"$hash\"}"
  }
  File(args[1],"loaded-code-sources.json").writeText("["+rows.joinToString()+"]\n")
}
