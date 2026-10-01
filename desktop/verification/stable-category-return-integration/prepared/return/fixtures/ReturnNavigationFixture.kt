package com.bilipai.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.core.ui.transition.VideoCardTransitionExposure
import com.android.purebilibili.core.util.CardPositionManager
import com.android.purebilibili.navigation3.*

/** Synthetic Root admission only; actual original snapshot/clock/policies/CPM classes are used.
 * No HWND, playback, account, Main consumer, persistence, predictor or pixels are exercised. */
fun main() {
    var alive=true
    var accepted=true
    var uptime=1_000L
    val host=Offset(30f,50f)
    fun owner()=DesktopHomeReturnNavigationOwner({alive},{ b -> if(alive) { b();true } else false },
        { b -> if(accepted) { b();true } else false },{host},{uptime},VideoCardTransitionClock(),
        {true},{true},{false})
    fun record(bvid:String,route:String,left:Float=120f) = CardPositionManager.recordVideoCardPosition(
        bvid,route,Rect(left,230f,left+180f,390f),1000f,1000f,density=1f,bottomBarHeightDp=0f,
        coverBounds=Rect(left,230f,left+180f,330f),sourceCornerDp=16)
    val root=owner()
    record("BVA","home?category=POPULAR")
    var actions=0
    accepted=false
    check(!root.enterVideo("BVA","home?category=POPULAR","coverA",BiliPaiNavKey.Home,false,
        setOf("home")){_,_->actions++})
    check(actions==0 && root.session.value.transitionSession==null)
    println("PASS rejected checkpoint does not capture or enter")
    accepted=true
    check(root.enterVideo("BVA","home?category=POPULAR","coverA",BiliPaiNavKey.Home,false,
        setOf("home")){source,session->check(source.route=="home?category=POPULAR");check(session.bvid=="BVA");actions++})
    val frozen=root.session.value.transitionSession!!
    check(frozen.cardBounds==Rect(120f,230f,300f,390f))
    check(frozen.hostOriginInRoot==host)
    CardPositionManager.clear()
    check(root.sourceMetadata().sourceBounds==frozen.cardBounds)
    check(root.sourceMetadataInCapturedHost().sourceBounds==Rect(90f,180f,270f,340f))
    println("PASS immutable click geometry survives manager clearing and uses captured host origin")
    uptime=1_500L
    check(root.returnFromVideo(BiliPaiNavKey.VideoDetail("BVA"),BiliPaiNavKey.Home,false){actions++})
    check(root.session.value.isReturningFromDetail && root.session.value.isQuickReturnFromDetail)
    check(root.consumeReturning())
    check(!root.session.value.isReturningFromDetail && !root.session.value.isQuickReturnFromDetail)
    println("PASS actual accepted return at original 500ms boundary and consume")
    uptime=2_000L
    record("BVA","home")
    root.enterVideo("BVA","home","coverA",BiliPaiNavKey.Home,false,setOf("home")){_,_->}
    uptime=2_501L
    root.returnFromVideo(BiliPaiNavKey.VideoDetail("BVA"),BiliPaiNavKey.Home,false){}
    check(root.session.value.isReturningFromDetail && !root.session.value.isQuickReturnFromDetail)
    println("PASS 501ms return is not quick")
    root.consumeReturning()
    record("BVB","video/BVA",450f)
    uptime=3_000L
    root.enterVideo("BVB","video/BVA","coverB",BiliPaiNavKey.VideoDetail("BVA"),true,setOf("home")){_,_->}
    check(root.session.value.previousTransitionSessions.size==1)
    root.returnFromVideo(BiliPaiNavKey.VideoDetail("BVB"),BiliPaiNavKey.VideoDetail("BVA"),true){}
    root.onRelatedReturnExposure(true,VideoCardTransitionExposure.Idle)
    check(root.session.value.transitionSession!!.bvid=="BVB")
    root.onRelatedReturnExposure(true,VideoCardTransitionExposure.Returning)
    check(root.session.value.transitionSession!!.bvid=="BVB")
    root.onRelatedReturnExposure(true,VideoCardTransitionExposure.Idle)
    check(root.session.value.transitionSession!!.bvid=="BVA")
    check(CardPositionManager.lastClickedVideoSourceKey=="home:BVA")
    println("PASS related pop restores original parent only after non-idle then idle")
    val snapshot=root.session.value
    alive=false
    check(!root.consumeReturning())
    check(!root.enterVideo("BVC","home",null,BiliPaiNavKey.Home,false,setOf("home")){_,_->error("retired action")})
    check(root.session.value==snapshot)
    alive=true;root.close()
    check(!root.returnFromVideo(BiliPaiNavKey.VideoDetail("BVA"),BiliPaiNavKey.Home,false){error("closed action")})
    check(root.session.value==snapshot)
    println("PASS retired epoch predicate and closed owner reject old UI effects")
    val foreign=VideoCardTransitionSession.create("BVX",BiliPaiVideoSource("home","home:BVY"),
        Rect(0f,0f,100f,100f),sourceCornerDp=10,cardSourceDirection=BiliPaiNavCardSourceDirection.SOURCE_LEFT,
        coverIdentity="coverX",cardFullyVisible=true,isSingleColumnCard=false,hostOriginInRoot=host)
    check(foreign.cardBounds==null && !foreign.cardFullyVisible && foreign.cardSourceDirection==BiliPaiNavCardSourceDirection.NONE)
    println("PASS exact original session factory strips mismatched source geometry")
    println("RESULT 7 groups; source-only prepared adapter; Main/nav/renderer/HTTP/Window=false")
}
