package com.bilipai.desktop.settings.proof
import com.bilipai.desktop.settings.DesktopHomeCardPreferencesTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.*
fun main(args:Array<String>) {
 val instance=DesktopHomeCardPreferencesTest()
 val results=instance.javaClass.declaredMethods.filter{it.isAnnotationPresent(Test::class.java)}.sortedBy{it.name}.map{method->
  try{check(method.returnType==Void.TYPE);method.invoke(instance);println("PASS ${method.name}");buildJsonObject{put("method",method.name);put("passed",true)}}
  catch(failure:java.lang.reflect.InvocationTargetException){throw failure.targetException}
 }
 check(results.size==10);Files.writeString(Path.of(args[0]),buildJsonObject{put("passed",true);put("actualAnnotatedUnitMethods",10);put("checks",JsonArray(results))}.toString())
}
