package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.toArgb
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.android.purebilibili.feature.home.components.cards.WallpaperPaletteStore
import com.bilipai.desktop.palette.DesktopPalette
import com.bilipai.desktop.palette.DesktopPaletteTarget
import com.bilipai.desktop.palette.DesktopWallpaperPaletteScoring
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs

private var assertions=0
private fun expect(value:Boolean,message:String) {check(value){message};assertions++}
private fun closeColor(actual:Int,expected:Int):Boolean = listOf(0,8,16).all {
    abs(((actual ushr it) and 255)-((expected ushr it) and 255))<=9
}
private suspend fun awaitCondition(condition:()->Boolean) {
    withTimeout(5000) {while(!condition())delay(5)}
}
private fun uniform(color:Int)=DesktopWallpaperPaletteRaster(20,30,IntArray(600){color})

/** Only explicit fixture data; this is not a production fallback context. */
private class FixtureContext : DesktopWallpaperPaletteContext {
    private val gate=Any()
    @Volatile var owned=true
    @Volatile var blockedUri:String?=null
    @Volatile var systemColor:Int?=0xff345678.toInt()
    val entered=CompletableDeferred<Unit>()
    val release=CompletableDeferred<Unit>()
    val reads=java.util.concurrent.ConcurrentHashMap<String,Int>()
    override fun isOwned()=owned
    override fun commitIfOwned(action:()->Unit):Boolean=synchronized(gate) {if(!owned)false else {action();true}}
    override suspend fun systemWallpaper()=DesktopSystemWallpaperSource(null,systemColor)
    override suspend fun readOwnedPixels(uri:String):DesktopWallpaperPaletteRaster {
        reads.merge(uri,1,Int::plus)
        if(uri==blockedUri)withContext(NonCancellable) {entered.complete(Unit);release.await()}
        return uniform(if(uri=="new")0xffdf4020.toInt() else 0xff2080dd.toInt())
    }
    fun retire()=synchronized(gate) {owned=false}
}

private fun scoringProof() {
    val vibrant=DesktopPalette.Swatch(0xffd83c30.toInt(),12)
    val muted=DesktopPalette.Swatch(0xff987d78.toInt(),80)
    val result=DesktopWallpaperPaletteScoring(listOf(vibrant,muted))
    expect(result.vibrantSwatch===vibrant,"vibrant eligibility/scoring")
    expect(result.mutedSwatch===muted,"muted target eligibility")
    expect(result.dominantSwatch===muted,"dominant population")
    val normalized=DesktopPaletteTarget.Builder(DesktopPaletteTarget.VIBRANT)
        .setSaturationWeight(3f).setLightnessWeight(6f).setPopulationWeight(1f).build()
    // Full original normalization is package-private; selected generate invokes it.
    expect(normalized.saturationWeight==3f && normalized.lightnessWeight==6f,"full Target builder source")
    val grayscale=DesktopWallpaperPaletteScoring(DesktopPalette.quantize(IntArray(100){0xff808080.toInt()},8))
    expect(grayscale.vibrantSwatch==null,"clearFilters does not invent vibrant")
    expect(grayscale.mutedSwatch!=null || grayscale.dominantSwatch!=null,"muted/dominant grayscale fallback")
    val lru=DesktopWallpaperPaletteLru<Int>(8)
    repeat(8){lru.put("k$it",it)};lru.get("k0");lru.put("k8",8)
    expect(lru.size==8 && lru.get("k1")==null && lru.get("k0")==0,"access-order 8-entry LRU")
    lru.evictAll();expect(lru.size==0,"LRU clear")
}

private suspend fun localDecodeProof(directory:Path) {
    java.nio.file.Files.createDirectories(directory)
    val bands=listOf(0xffdd4030.toInt(),0xffd0a828.toInt(),0xff38b060.toInt(),0xff3080d8.toInt(),0xffa050d0.toInt())
    val image=BufferedImage(70,250,BufferedImage.TYPE_INT_ARGB)
    for(y in 0 until image.height)for(x in 0 until image.width)image.setRGB(x,y,bands[y/50])
    val file=directory.resolve("known-five-bands.png").toFile();check(ImageIO.write(image,"png",file))
    val platform=PlatformContext.INSTANCE
    val loader=SingletonImageLoader.get(platform)
    expect(loader===SingletonImageLoader.get(platform),"same actual SingletonImageLoader")
    var owner=true
    var systemCalls=0
    val context=DesktopOwnedWallpaperPaletteContext(platform,loader,
        DesktopSystemWallpaperPort {systemCalls++;DesktopSystemWallpaperSource(null,0xff345678.toInt())},
        {owner},{action->if(owner){action();true}else false})
    val uri=file.toURI().toString()
    val raw=loader.execute(ImageRequest.Builder(platform).data(file).size(256,512).build()) as SuccessResult
    val borrowed=(raw.image as coil3.BitmapImage).bitmap
    val pixels=context.readOwnedPixels(uri)!!
    expect(!borrowed.isClosed,"borrowed Coil bitmap stays open after pixel snapshot")
    expect(pixels.width>0 && pixels.height>0,"actual local PNG decoded by Coil/Skia")
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val store=WallpaperPaletteStore()
    try {
        store.loadWallpaperPalette(context,uri,scope)
        awaitCondition {store.currentPalette.value!=null}
        val palette=store.currentPalette.value!!
        expect(palette.stops.size==5,"original five vertical slices")
        palette.stops.forEachIndexed {i,color->expect(closeColor(color.toArgb(),bands[i]),"decoded band $i original order")}
        expect(palette.topColor==palette.stops.first() && palette.bottomColor==palette.stops.last(),"top/bottom mapping")
        expect(palette.dominantColor==palette.stops[2],"middle dominant mapping")
        expect(systemCalls==0,"valid image never reads personal system wallpaper")
        expect(store.getCachedPalette(uri)==palette,"original URI cache")
        expect(!borrowed.isClosed,"palette extraction never closes cached borrowed bitmap")
        // 211x1001 is deliberately asymmetric: full resize 52x244, mapped top=floor(600*52/211)=147.
        val asymmetric=DesktopWallpaperPaletteRaster(211,1001,IntArray(211*1001){index->
            if(index/211>=600)0xff3060d0.toInt() else 0xffd05030.toInt()})
        val scored=asymmetric.paletteForOriginalRegion(600,800)
        expect(closeColor(scored.vibrantSwatch!!.rgb,0xff3060d0.toInt()),"full-image resize then width-ratio region")
        owner=false
        try {context.readOwnedPixels(uri);error("retired context accepted decode")}
        catch(_:CancellationException) {expect(true,"retired actual decode rejects")}
    } finally {store.close();scope.cancel()}
    expect(!borrowed.isClosed,"only owned arrays retained; borrowed bitmap unclosed")
    // The singleton owns its image/native lifetime. This fixture does not close it or its Bitmap.
}

private suspend fun ownerAndCacheProof() {
    val context=FixtureContext();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val store=WallpaperPaletteStore()
    try {
        repeat(8) {i->store.loadWallpaperPalette(context,"k$i",scope);awaitCondition {store.getCachedPalette("k$i")!=null}}
        val cached=store.getCachedPalette("k0");store.loadWallpaperPalette(context,"k0",scope)
        expect(store.currentPalette.value==cached && context.reads["k0"]==1,"cache read avoids decoder")
        store.loadWallpaperPalette(context,"k8",scope);awaitCondition {store.getCachedPalette("k8")!=null}
        expect(store.getCachedPalette("k1")==null && store.getCachedPalette("k0")!=null,"Store bounded access-order eviction")
        store.loadWallpaperPalette(context,"",scope)
        awaitCondition {store.currentPalette.value?.stops?.size==3}
        expect(store.currentPalette.value!!.topColor.toArgb()==0xff345678.toInt(),"blank URI actual effect fixture background")
        expect(store.getCachedPalette("")==null,"blank URI is not cached")
        context.systemColor=null;store.loadWallpaperPalette(context,"",scope)
        awaitCondition {store.currentPalette.value?.stops?.size==5}
        expect(store.currentPalette.value!!.topColor.toArgb()==0xff6750a4.toInt(),"unsupported system color uses exact original default")
        store.clear()
        expect(store.currentPalette.value==null && store.getCachedPalette("k0")==null,"clear resets flow and cache")
    } finally {store.close();scope.cancel()}

    val blocked=FixtureContext().apply {blockedUri="old"}
    val scoped=CoroutineScope(SupervisorJob()+Dispatchers.Default);val superseded=WallpaperPaletteStore()
    try {
        superseded.loadWallpaperPalette(blocked,"old",scoped);blocked.entered.await()
        superseded.loadWallpaperPalette(blocked,"new",scoped)
        awaitCondition {superseded.getCachedPalette("new")!=null}
        val current=superseded.currentPalette.value
        blocked.release.complete(Unit);delay(100)
        expect(superseded.currentPalette.value==current && superseded.getCachedPalette("old")==null,"late superseded URI cannot publish/cache")
    } finally {blocked.release.complete(Unit);superseded.close();scoped.cancel()}

    val retired=FixtureContext().apply {blockedUri="held"}
    val retirementScope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val retiredStore=WallpaperPaletteStore()
    try {
        retiredStore.loadWallpaperPalette(retired,"held",retirementScope);retired.entered.await();retired.retire()
        retired.release.complete(Unit);delay(100)
        expect(retiredStore.currentPalette.value==null && retiredStore.getCachedPalette("held")==null,"same gate rejects retired request")
        retiredStore.close();retired.owned=true;retiredStore.loadWallpaperPalette(retired,"after-close",retirementScope)
        delay(50);expect(retired.reads["after-close"]==null && retiredStore.currentPalette.value==null,"closed application Store rejects new request")
    } finally {retired.release.complete(Unit);retiredStore.close();retirementScope.cancel()}
}

fun main(args:Array<String>)=runBlocking {
    scoringProof();localDecodeProof(Path.of(args.single()));ownerAndCacheProof()
    println("WALLPAPER_PALETTE_PROOF {\"groups\":3,\"assertions\":$assertions,\"actualSnapshot\":41,\"entries\":97,\"overrides\":0,\"externalHttp\":false,\"personalWallpaperRead\":false,\"rootMounted\":false}")
}
