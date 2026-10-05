package com.bilipai.desktop.ui

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Proxy
import kotlin.test.*

/** Exercises the actual sole generated preview controller/request. Reflection
 * is confined to the test, so the private original controller stays private.
 * No AWT window, image network, media or account is constructed. */
class DesktopWindowsCommentPreviewRequestTest {
    private val requestType = Class.forName("com.android.purebilibili.feature.dynamic.components.ImagePreviewOverlayRequest")
    private val controllerType = Class.forName("com.android.purebilibili.feature.dynamic.components.ImagePreviewOverlayController")
    private val controller = controllerType.getDeclaredField("INSTANCE").apply { isAccessible=true }.get(null)
    private fun call(name: String,vararg arguments: Any?): Any? = controllerType.declaredMethods.single {
        it.name==name && it.parameterCount==arguments.size
    }.apply { isAccessible=true }.invoke(controller,*arguments)
    private fun value(): Any? = (call("getRequest") as StateFlow<*>).value
    private fun field(request: Any,name: String) = requestType.getDeclaredField(name).apply { isAccessible=true }.get(request)
    private fun request(token: Long,presentation: DesktopWindowsCommentPresentation?,rect: Rect?): Any {
        val platform=Proxy.newProxyInstance(DesktopDynamicCardPlatform::class.java.classLoader,
            arrayOf(DesktopDynamicCardPlatform::class.java)) { _,method,_ -> error("Preview lifetime test must not call ${method.name}") }
        val dismiss: () -> Unit = {}
        return requestType.declaredConstructors.single { it.parameterCount==15 }.apply { isAccessible=true }.newInstance(
            token,platform,presentation,listOf("https://example.invalid/fixture.png"),emptyMap<String,String>(),0,
            rect,emptyMap<Int,Rect>(),rect,"fixture",0f,null,true,null,dismiss)
    }
    @Test fun retiringOldExactTokenCannotDismissSuccessorOrAnotherPagePreview(): Unit {
        assertNull(value())
        val old=DesktopWindowsCommentPresentation(Any(),null,{true},{it();true})
        val next=DesktopWindowsCommentPresentation(Any(),null,{true},{it();true})
        try {
            call("show",request(101,old,null));call("show",request(102,next,null))
            val accepted=assertNotNull(value());old.close();call("dismiss",101L)
            assertSame(accepted,value());assertSame(next,field(accepted,"desktopNativePresentation"))
            call("show",request(103,null,null));call("dismiss",102L)
            assertEquals(103L,field(assertNotNull(value()),"token"))
        } finally { call("dismiss",103L);call("dismiss",102L);call("dismiss",101L);old.close();next.close() }
    }
    @Test fun nativeOwnerUsesOriginalNullAnchorWhileLegacyKeepsMainAnchor(): Unit {
        assertNull(value());val rect=Rect(10f,20f,30f,40f)
        val p=DesktopWindowsCommentPresentation(Any(),null,{true},{it();true})
        try {
            call("prepareSourceTransition",rect,"fixture")
            call("show",request(201,p,rect))
            assertNull(field(assertNotNull(value()),"activeSourceRect"))
            assertNull((call("getActiveSourceKey") as StateFlow<*>).value)
            call("dismiss",201L)
            call("show",request(202,null,rect))
            assertEquals(rect,field(assertNotNull(value()),"activeSourceRect"))
        } finally { call("dismiss",202L);call("dismiss",201L);p.close() }
    }
}
