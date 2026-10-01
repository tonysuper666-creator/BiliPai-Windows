package com.bilipai.desktop.ui

import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private class RecordedInput(bytes:ByteArray):ByteArrayInputStream(bytes) {
    var closes=0
    override fun close(){closes++;super.close()}
}
fun main()=runBlocking {
    var assertions=0
    fun proof(condition:Boolean){check(condition);assertions++}
    var monitorReads=0
    val monitor={monitorReads++;DesktopPhysicalDisplayOrientation.LANDSCAPE}
    proof(desktopDanmakuPresentation(WindowPlacement.Floating,monitor)==DesktopDanmakuPresentation.INLINE)
    proof(desktopDanmakuPresentation(WindowPlacement.Maximized,monitor)==DesktopDanmakuPresentation.INLINE)
    proof(monitorReads==0)
    proof(desktopDanmakuPresentation(WindowPlacement.Fullscreen,monitor)==DesktopDanmakuPresentation.FULLSCREEN_LANDSCAPE)
    proof(monitorReads==1)
    proof(desktopDanmakuPresentation(WindowPlacement.Fullscreen){DesktopPhysicalDisplayOrientation.PORTRAIT}==DesktopDanmakuPresentation.FULLSCREEN_PORTRAIT)
    proof(DesktopDanmakuPresentation.INLINE.originalScope()==com.android.purebilibili.core.store.DanmakuSettingsScope.PORTRAIT)
    proof(DesktopDanmakuPresentation.FULLSCREEN_LANDSCAPE.originalScope()==com.android.purebilibili.core.store.DanmakuSettingsScope.LANDSCAPE)
    proof(DesktopDanmakuPresentation.FULLSCREEN_PORTRAIT.originalScope()==com.android.purebilibili.core.store.DanmakuSettingsScope.PORTRAIT)
    val rootPage=Job()
    val modal=Job(rootPage)
    val owned=AtomicBoolean(true)
    var checkpoints=0
    val raw=RecordedInput(byteArrayOf(1,2,3,4,5,6,7))
    val input=DesktopOwnedDanmakuRuleInput(raw,modal){checkpoints++;modal.ensureActive();if(!owned.get())throw CancellationException("retired owner")}
    proof(input.read()==1)
    val bytes=ByteArray(2)
    proof(input.read(bytes)==2&&bytes.contentEquals(byteArrayOf(2,3)))
    proof(input.skip(1)==1L)
    proof(input.read(bytes,0,1)==1&&bytes[0]==5.toByte())
    proof(input.available()==2)
    proof(checkpoints==10)
    modal.cancel()
    proof(raw.closes==1)
    proof(rootPage.isActive)
    proof(runCatching{input.read()}.exceptionOrNull() is CancellationException)
    input.close()
    proof(raw.closes==1)
    val otherJob=Job(rootPage)
    val raw2=RecordedInput(byteArrayOf(1))
    val input2=DesktopOwnedDanmakuRuleInput(raw2,otherJob){otherJob.ensureActive();if(!owned.get())throw CancellationException("retired source")}
    owned.set(false)
    proof(runCatching{input2.skip(1)}.exceptionOrNull() is CancellationException)
    proof(raw2.closes==1)
    proof(otherJob.isActive)
    val raw3=RecordedInput(byteArrayOf(1))
    val input3=DesktopOwnedDanmakuRuleInput(raw3,otherJob){otherJob.ensureActive()}
    input3.close()
    proof(raw3.closes==1)
    proof(runCatching{input3.read()}.exceptionOrNull() is IOException)
    val raw4=object:java.io.InputStream(){var closed=false;override fun read():Int{owned.set(false);return 9};override fun close(){closed=true}}
    owned.set(true)
    val input4=DesktopOwnedDanmakuRuleInput(raw4,otherJob){otherJob.ensureActive();if(!owned.get())throw CancellationException("post-read owner retired")}
    proof(runCatching{input4.read()}.exceptionOrNull() is CancellationException)
    proof(raw4.closed)
    rootPage.cancel()
    proof(!otherJob.isActive)
    println("ROOT_DANMAKU_CONSUMER_PROOF PASS assertions=$assertions groups=3 nativeWindowAndChooserNotExercised=true")
}
