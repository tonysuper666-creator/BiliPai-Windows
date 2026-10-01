@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.home.components.*
import com.bilipai.desktop.appearance.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import top.yukonga.miuix.kmp.blur.*
import java.nio.file.*
import java.nio.file.Path as NioPath
import java.security.MessageDigest

internal class ComposerScene(val scene:ImageComposeScene) {
    var nanos=0L;var pointers=0;var imeActions=0;var semanticsTextEntries=0
    fun nodes():List<SemanticsNode> {
        fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}
    }
    fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    fun field()=nodes().last{it.config.getOrNull(SemanticsActions.SetText)!=null}
    fun value()=field().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    suspend fun frame(){nanos+=32_000_000L;scene.render(nanos).close();delay(3)}
    suspend fun await(label:String,condition:()->Boolean){
        try{withTimeout(5000){while(!condition())frame()};repeat(8){frame()}}
        catch(e:TimeoutCancellationException){error("$label: ${labels()}")}
    }
    suspend fun press(n:SemanticsNode){
        val pos=n.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press,pos,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,pos,timeMillis=nanos/1_000_000+30,buttons=PointerButtons())
        pointers++;repeat(8){frame()}
    }
    suspend fun input(text:String){
        // Actual semantics editor action, NOT claimed physical character/OS IME input.
        check(checkNotNull(field().config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(text)))
        semanticsTextEntries++;await("original field receives editor action"){value()==text}
    }
    suspend fun send(){
        val node=nodes().last{it.config.getOrNull(SemanticsActions.OnImeAction)!=null}
        check(checkNotNull(node.config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke())
        imeActions++;repeat(10){frame()}
    }
    fun screenshot(path:NioPath):String {
        val bytes=scene.render(nanos+1).use{checkNotNull(it.encodeToData(EncodedImageFormat.PNG)).bytes}
        Files.write(path,bytes)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    }
}

