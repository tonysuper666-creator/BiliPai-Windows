package com.bilipai.desktop.ui
import com.android.purebilibili.feature.video.usecase.VideoLoadResult
/** Required same repository/authorization/generation request binding. The complete
 * original UseCase implements this port; no substitute load algorithm or default.
 */
internal interface DesktopOriginalVideoLoadPort {
 suspend fun loadVideo(bvid:String,aid:Long=0,cid:Long=0L,defaultQuality:Int=64,
 audioQualityPreference:Int=-1,videoCodecPreference:String="hev1",videoSecondCodecPreference:String="avc1",
 audioLang:String?=null,playWhenReady:Boolean=true,isAv1SupportedOverride:Boolean?=null,
 isHdrSupportedOverride:Boolean?=null,isDolbyVisionSupportedOverride:Boolean?=null,
 onProgress:(String)->Unit={}):VideoLoadResult
}
