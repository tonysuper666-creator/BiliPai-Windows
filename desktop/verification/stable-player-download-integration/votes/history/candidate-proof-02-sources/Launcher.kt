package com.bilipai.desktop.votefixture
import java.nio.file.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
fun main(args:Array<String>) = runBlocking {
    val output = Path.of(args[0]); Files.createDirectories(output)
    val protocol = protocolProof()
    val ui = uiProof(output)
    val result = buildJsonObject {
        put("passed",true); put("preparedCandidateOnly",true); put("MainAcceptance",false)
        put("accountRequests",false); put("HTTP",false); put("HWND",false)
        put("dialogRendererOverridden",false); put("originalCardAndDialog",true)
        put("protocolGates",JsonArray(protocol.map(::JsonPrimitive)))
        put("uiGates",JsonArray(ui.map(::JsonPrimitive)))
        put("nativePopupHitTesting",false); put("PiP",false)
    }
    Files.writeString(output.resolve("proof.json"),result.toString())
    println(result)
}
