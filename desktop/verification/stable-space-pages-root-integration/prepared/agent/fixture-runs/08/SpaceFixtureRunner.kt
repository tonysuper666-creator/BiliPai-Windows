package com.bilipai.desktop.ui
import java.nio.file.*
fun main(args:Array<String>) {
 val methods=DesktopOriginalSpacePagesTest::class.java.declaredMethods.filter {it.isAnnotationPresent(org.junit.jupiter.api.Test::class.java)}.sortedBy {it.name}
 val results=mutableListOf<String>();var failure:Throwable?=null
 for(method in methods) {try {method.invoke(DesktopOriginalSpacePagesTest());results+="{\"method\":\"${method.name}\",\"passed\":true}";println("PASS ${method.name}")}
 catch(error:Throwable){val actual=error.cause?:error;failure=actual;results+="{\"method\":\"${method.name}\",\"passed\":false}";println("FAIL ${method.name}");actual.printStackTrace();break}}
 Files.writeString(Path.of(args[0]),"{\"actualMethods\":${methods.size},\"passed\":${failure==null},\"rootAccepted\":false,\"realAccountRequests\":0,\"methods\":[${results.joinToString(",")}]}")
 if(failure!=null) throw failure
}
