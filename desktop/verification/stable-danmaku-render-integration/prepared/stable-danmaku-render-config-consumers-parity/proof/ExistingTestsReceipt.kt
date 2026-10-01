package com.bilipai.desktop.danmaku
fun main() {
    val fixture=DanmakuTest()
    val methods=fixture.javaClass.declaredMethods.filter {it.isAnnotationPresent(org.junit.jupiter.api.Test::class.java)}.sortedBy {it.name}
    methods.forEach {m ->try {m.invoke(fixture)}catch(e:java.lang.reflect.InvocationTargetException){throw e.targetException}}
    println("Existing actual DanmakuTest: PASS ${methods.size} methods; explicit test-only config adapter; no HWND/HTTP")
}
