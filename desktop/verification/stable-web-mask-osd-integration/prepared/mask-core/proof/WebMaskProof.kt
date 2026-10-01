package com.bilipai.desktop.danmaku

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.danmaku.engine.DanmakuMaskFrame
import com.bilipai.desktop.ui.projectOriginalDanmakuRendererSettings
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

private val assertions=AtomicInteger()
private fun verify(value:Boolean,label:String){check(value){label};assertions.incrementAndGet()}
private val square="<svg viewBox='0 0 100 100'><path d='M25 25 H75 V75 H25 Z'/></svg>"
private fun gzip(raw:ByteArray):ByteArray=ByteArrayOutputStream().also { out->GZIPOutputStream(out).use {it.write(raw)} }.toByteArray()
private fun mask(vararg chunks:Pair<Long,List<String>>):ByteArray {
    val zipped=chunks.map { (_,paths)->gzip(ByteArray(16)+(paths.joinToString(""){"data:image/svg+xml;base64,"+Base64.getEncoder().encodeToString(it.toByteArray())}).toByteArray()) }
    val headerSize=16+16*chunks.size
    val buffer=ByteBuffer.allocate(headerSize+zipped.sumOf{it.size}).order(ByteOrder.BIG_ENDIAN)
    buffer.put("MASK".toByteArray());buffer.putInt(1);buffer.putInt(0);buffer.putInt(chunks.size)
    var offset=headerSize
    chunks.forEachIndexed { i,(start,_)->buffer.putLong(start);buffer.putLong(offset.toLong());offset+=zipped[i].size }
    zipped.forEach(buffer::put)
    return buffer.array()
}
private class Source(var bytes:ByteArray):DesktopDanmakuSource {
    val calls=AtomicInteger();val cancelled=AtomicInteger();var gate:CompletableDeferred<Unit>?=null
    var lastUrl=""
    override suspend fun metadata(cid:Long,aid:Long)=error("No extra metadata route")
    override suspend fun segment(cid:Long,index:Int)=error("No extra standard route")
    override suspend fun xml(cid:Long)=error("No extra XML route")
    override suspend fun special(url:String):ByteArray {
        calls.incrementAndGet();lastUrl=url
        try{gate?.await();return bytes}catch(c:CancellationException){cancelled.incrementAndGet();throw c}
    }
}
private class Owner(override val scope:CoroutineScope,override val webMaskTransport:DesktopDanmakuSource):DesktopOriginalWebMaskOwner() {
    override val webMaskLock=Any()
    public override var cachedCid=1L
    public override var loadGeneration=1L
    public override var webMaskEnabled=true
    var position=0L;val paints=AtomicInteger()
    override fun webMaskPositionMs()=position
    override fun invalidateWebMaskPaint(){paints.incrementAndGet()}
}
private suspend fun awaitFrame(owner:Owner,time:Long=0L):DanmakuMaskFrame = withTimeout(6000){
    while(true){owner.currentWebMaskFrame(time)?.let{return@withTimeout it};delay(5)}
    error("unreachable")
}
fun main(args:Array<String>)=runBlocking {
    fun originalGuard(position:Long,start:Long,end:Long)=position>=start+5000L && position<=end-5000L
    verify(originalGuard(0,Long.MIN_VALUE,Long.MIN_VALUE),"fixed-tag uninitialized guard demonstrably overflows into false cache admission")
    val bytes=mask(0L to listOf(square,square),10_000L to listOf(square))
    val index=WebMaskParser.parseIndex(bytes)
    verify(index.size==2 && index[0].startTimeMs==0L && index[1].startTimeMs==10_000L,"full index order")
    verify(index[0].offset==48 && index[0].endOffset==index[1].offset,"full offset boundaries")
    val frames=WebMaskParser.parseWindow(bytes,0,0,40_000)
    verify(frames.size==3,"all original SVG frames")
    verify(frames[0].startTimeMs==0L&&frames[0].endTimeMs==5000L&&frames[1].startTimeMs==5000L&&frames[1].endTimeMs==10000L,"original inferred cadence")
    verify(frames[2].endTimeMs==20000L,"original final chunk fallback")
    verify(frames[0].sourceWidth==100&&frames[0].sourceHeight==100,"original viewBox dimensions")
    verify(WebMaskParser.parseWindow(bytes,100,6000,9000).size==1,"selected original time window")
    verify(WebMaskParser.parseIndex(bytes.copyOf(15)).isEmpty(),"short header")
    verify(WebMaskParser.parseIndex(bytes.clone().apply{this[0]=0}).isEmpty(),"signature rejected")
    verify(WebMaskParser.parseIndex(bytes.clone().apply{ByteBuffer.wrap(this).putInt(4,2)}).isEmpty(),"version rejected")
    verify(WebMaskParser.parseIndex(bytes.clone().apply{ByteBuffer.wrap(this).putInt(12,20001)}).isEmpty(),"count bound")
    verify(WebMaskParser.parseIndex(bytes.clone().apply{ByteBuffer.wrap(this).putLong(24,0)}).isEmpty(),"offset before index rejected")
    verify(WebMaskParser.parseIndex(bytes.clone().apply{ByteBuffer.wrap(this).putLong(40,48)}).isEmpty(),"nonincreasing offsets rejected")
    verify(WebMaskParser.parseWindow(bytes.clone().apply{this[index[0].offset]=0},30,0,40000).size==1,"corrupt gzip chunk skipped, later chunk retained")
    val huge=gzip(ByteArray(16*1024*1024+1));val oversized=ByteBuffer.allocate(32+huge.size).order(ByteOrder.BIG_ENDIAN).apply {put("MASK".toByteArray());putInt(1);putInt(0);putInt(1);putLong(0);putLong(32);put(huge)}.array()
    verify(WebMaskParser.parseWindow(oversized,30,0,40000).isEmpty(),"original inflated16MiB bound")
    val defaults=WebMaskParser.parseWindow(mask(0L to listOf("<svg><path d='M0 0 L10 0 L10 10 Z'/></svg>")),30,0,40000).single()
    verify(defaults.sourceWidth==1920&&defaults.sourceHeight==1080,"original missing viewBox default")
    val arc=DesktopWebMaskPath.createPathFromPathData("M25 50 A25 25 0 1 1 75 50 A25 25 0 1 1 25 50 Z")!!
    verify(arc.scaledArea(100,100,100,100).contains(50.0,50.0),"complete SVG arc carrier")
    val relative=DesktopWebMaskPath.createPathFromPathData("m10 10 h30 v30 h-30 z m50 0 c0 30 30 30 30 0 s-30 -30 -30 0 q0 10 10 10 t10 -10 z")!!
    verify(relative.scaledArea(100,100,100,100).contains(20.0,20.0),"relative/horizontal/vertical/curve/smooth/quad grammar")
    verify(DesktopWebMaskPath.createPathFromPathData("M0 0 "+"L1 1 ".repeat(20001))==null,"actual native path temporaries closed when Windows path budget rejects")
    val image=BufferedImage(200,100,BufferedImage.TYPE_INT_ARGB)
    val g=image.createGraphics();g.color=Color.RED;g.fillRect(0,0,200,100)
    val standard=g.create() as java.awt.Graphics2D
    applyDesktopWebMaskClip(standard,200,100,frames[0]);standard.color=Color.BLUE;standard.fillRect(0,0,200,100);standard.dispose()
    verify(image.getRGB(100,50)==Color.RED.rgb,"masked standard pixels retain video background")
    verify(image.getRGB(10,10)==Color.BLUE.rgb,"unmasked standard pixels painted")
    g.color=Color.GREEN;g.fillRect(99,49,2,2);g.dispose()
    verify(image.getRGB(100,50)==Color.GREEN.rgb,"later authored/UI layer not clipped")
    verify(shouldApplyDanmakuLoadResult(1,2,1,2)&&!shouldApplyDanmakuLoadResult(1,2,2,2)&&!shouldApplyDanmakuLoadResult(1,2,1,3),"original CID/load generation gate")
    verify(resolveDanmakuDriftSyncIntervalMs(1f)==3200L&&resolveDanmakuDriftSyncIntervalMs(2f)==900L,"original refresh poll speed policy")
    val projected=projectOriginalDanmakuRendererSettings(DanmakuSettings(),com.android.purebilibili.core.store.DanmakuSettings(smartOcclusion=true))
    verify(projected.smartOcclusionEnabled,"same original smart preference reaches scalar consumer")
    verify(!Json.encodeToString(DanmakuSettings.serializer(),projected).contains("smartOcclusion"),"ephemeral smart projection not serialized as another preference authority")
    val protocol=Source(bytes)
    verify(getDesktopOriginalWebMask(protocol," ")==null&&protocol.calls.get()==0,"original blank-mask request is absent")
    val cancellation=object:DesktopDanmakuSource by protocol {override suspend fun special(url:String):ByteArray=throw CancellationException("fixture")}
    var propagated=false
    try{getDesktopOriginalWebMask(cancellation,"//fixture.hdslb.com/mask")}catch(_:CancellationException){propagated=true}
    verify(propagated,"original transport cancellation propagates")
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val source=Source(bytes);val owner=Owner(scope,source)
    val alive=AtomicBoolean(true);val metadata=AtomicInteger()
    fun target()=DesktopOwnedWebMaskSource("BVfixture",owner.cachedCid,42,7,{alive.get()}, {bvid,cid->verify(bvid=="BVfixture"&&cid==owner.cachedCid,"required metadata identity");metadata.incrementAndGet();PlayerInfoData(dmMask=DanmakuMaskInfo(maskUrl="//fixture.hdslb.com/mask",fps=30))})
    owner.bindWebMaskSource(target());awaitFrame(owner)
    verify(source.calls.get()==1&&metadata.get()==1&&source.lastUrl=="https://fixture.hdslb.com/mask","same special route and original URL normalization")
    verify(owner.currentWebMaskFrame(5000)?.startTimeMs==5000L,"half-open boundary selects current frame")
    owner.webMaskEnabled=false;owner.onWebMaskSettingChanged()
    verify(owner.currentWebMaskFrame(0)==null,"smart off clears active paint")
    owner.webMaskEnabled=true;owner.onWebMaskSettingChanged();awaitFrame(owner)
    verify(source.calls.get()==1&&metadata.get()==1,"smart reenable reuses same owned bytes")
    owner.position=12000;owner.refreshWebMaskWindow(12000);awaitFrame(owner,12000)
    verify(owner.currentWebMaskFrame(12000)?.startTimeMs==10000L,"seek window aligned with media time")
    alive.set(false);owner.validateWebMaskOwnership()
    verify(owner.currentWebMaskFrame(12000)==null,"retired epoch/source rejects native paint")
    alive.set(true);source.gate=CompletableDeferred();owner.bindWebMaskSource(target())
    withTimeout(2000){while(source.calls.get()<2)delay(5)}
    owner.webMaskEnabled=false;owner.onWebMaskSettingChanged()
    withTimeout(2000){while(source.cancelled.get()<1)delay(5)}
    verify(owner.currentWebMaskFrame(0)==null,"toggle cancels current mask network child")
    source.gate=null;owner.webMaskEnabled=true;owner.onWebMaskSettingChanged();awaitFrame(owner)
    owner.cachedCid=2;owner.loadGeneration=2;owner.bindWebMaskSource(null)
    verify(owner.currentWebMaskFrame(0)==null,"offline/live/null retires prior bytes/frames")
    val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val beforeCalls=source.calls.get()
    owner.bindWebMaskSource(DesktopOwnedWebMaskSource("BVfixture",2,43,7,{true},{_,_->
        entered.complete(Unit)
        withContext(NonCancellable){release.await()}
        PlayerInfoData(dmMask=DanmakuMaskInfo(maskUrl="//fixture.hdslb.com/mask",fps=30))
    }))
    withTimeout(2000){entered.await()}
    owner.cachedCid=3;owner.loadGeneration=3;owner.bindWebMaskSource(null);release.complete(Unit);delay(60)
    verify(source.calls.get()==beforeCalls&&owner.currentWebMaskFrame(0)==null,"stale uncooperative metadata cannot start byte fetch or publish")
    val budgetSource=Source(mask(0L to List(2403){square}));val budgetOwner=Owner(scope,budgetSource);val beforePaint=budgetOwner.paints.get()
    budgetOwner.bindWebMaskSource(DesktopOwnedWebMaskSource("BVfixture",1,44,7,{true},{_,_->PlayerInfoData(dmMask=DanmakuMaskInfo(maskUrl="//fixture.hdslb.com/mask",fps=60))}))
    withTimeout(6000){while(budgetOwner.paints.get()<beforePaint+3)delay(5)}
    verify(budgetOwner.webMaskAvailable()&&budgetOwner.currentWebMaskFrame(0)==null,"optional retained mask cache has bounded frames; source/player stays active")
    budgetOwner.retireWebMaskSource()
    verify(owner.paints.get()>5,"same owner invalidates actual paint")
    scope.cancel()
    val report="{\"status\":\"PASS\",\"groups\":5,\"assertions\":$assertions,\"externalHttp\":false,\"rootWindowRuntime\":false,\"realSkiaSvgAbi\":true,\"realJava2DPaint\":true}"
    Files.writeString(Path.of(args[0],"proof-result.json"),report+"\n");println(report)
}
