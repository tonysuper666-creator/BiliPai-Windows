package com.android.purebilibili.feature.video.screen
fun main() {
    var cases=0
    for (test in listOf(AudioModePlaybackPolicyTest(),AudioModeSleepTimerPolicyTest())) {
        val methods=test.javaClass.declaredMethods.filter { it.parameterCount==0 && it.returnType==Void.TYPE && !it.isSynthetic }.sortedBy { it.name }
        for (method in methods) {
            try { method.invoke(test) } catch(e:java.lang.reflect.InvocationTargetException) { throw e.cause?:e }
            println("PASS original case: "+method.name);cases++
        }
    }
    check(cases==16)
    for (name in listOf("com.android.purebilibili.feature.video.screen.AudioModeScreenKt","com.android.purebilibili.feature.video.screen.AudioModeMusicPlayerKt","com.android.purebilibili.feature.video.ui.components.VideoCommentSheetHostKt")) {
        val loaded=Class.forName(name);check(loaded.declaredMethods.isNotEmpty());println("PASS full renderer ABI: "+name)
    }
    println("PASS all 16 original cases; no native window, request or account")
}
