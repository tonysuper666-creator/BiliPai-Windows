package com.bilipai.desktop.brand

import kotlinx.serialization.json.*
import kotlin.test.*

class BrandMotionSchemaTest {
    private val animation = DesktopMaidAnimation.EMPTY
    private fun original(): JsonObject = Json.parseToJsonElement(
        BrandMotionTestResources.bytes(animation.asset.jsonFileName).toString(Charsets.UTF_8),
    ).jsonObject
    private fun rejected(root: JsonObject): BrandMotionDecodeResult.Rejected = assertIs(
        BrandMotionDecoder.parseRestrictedJson(root.toString().toByteArray(), animation.asset.pngFileName),
    )
    private fun firstLayer(root: JsonObject, change: (JsonObject) -> JsonObject): JsonObject {
        val layers = root.getValue("layers").jsonArray.toMutableList()
        layers[0] = change(layers[0].jsonObject)
        return JsonObject(root + ("layers" to JsonArray(layers)))
    }

    @Test fun unknownEffectAndMatteRejectWholeSceneInsteadOfDroppingContent() {
        val root = original()
        assertTrue(rejected(firstLayer(root) { JsonObject(it + ("ef" to JsonArray(emptyList()))) }).reason.contains("Unknown"))
        assertTrue(rejected(firstLayer(root) { JsonObject(it + ("tt" to JsonPrimitive(1))) }).reason.contains("Unknown"))
    }

    @Test fun unknownTrimRepeaterAndAnimatedPathAreNotSilentlyIgnored() {
        for (unknown in listOf("tm", "rp")) {
            val root = firstLayer(original()) {
                JsonObject((it - setOf("refId", "w", "h", "masksProperties", "hasMask")) + mapOf(
                    "ty" to JsonPrimitive(4), "shapes" to JsonArray(listOf(buildJsonObject { put("ty", unknown) })),
                ))
            }
            rejected(root)
        }
        val root = firstLayer(original()) {
            JsonObject((it - setOf("refId", "w", "h", "masksProperties", "hasMask")) + mapOf(
                "ty" to JsonPrimitive(4), "shapes" to JsonArray(listOf(buildJsonObject {
                    put("ty", "sh"); put("ks", buildJsonObject { put("a", 1); put("k", JsonArray(emptyList())) })
                })),
            ))
        }
        assertTrue(rejected(root).reason.contains("Animated paths"))
    }

    @Test fun cyclicAndMissingTransformParentsFailClosed() {
        rejected(firstLayer(original()) { JsonObject(it + ("parent" to it.getValue("ind"))) })
        rejected(firstLayer(original()) { JsonObject(it + ("parent" to JsonPrimitive(128))) })
    }

    @Test fun recursivePrecompositionCannotExpand() {
        val root = original()
        val assets = root.getValue("assets").jsonArray.toMutableList()
        val index = assets.indexOfFirst { "layers" in it.jsonObject }
        val asset = assets[index].jsonObject
        val child = asset.getValue("layers").jsonArray.single().jsonObject
        assets[index] = JsonObject(asset + ("layers" to JsonArray(listOf(JsonObject(child + mapOf(
            "ty" to JsonPrimitive(0), "refId" to asset.getValue("id"),
        ))))))
        assertTrue(rejected(JsonObject(root + ("assets" to JsonArray(assets)))).reason.contains("cyclic"))
    }

    @Test fun foreignImageAndUnknownTimeRemapCannotBeDeclaredReady() {
        val root = original()
        val assets = root.getValue("assets").jsonArray.map {
            if ("p" in it.jsonObject) JsonObject(it.jsonObject + ("u" to JsonPrimitive("https://example.invalid/"))) else it
        }
        rejected(JsonObject(root + ("assets" to JsonArray(assets))))
        rejected(firstLayer(root) { JsonObject(it + ("tm" to JsonPrimitive(0))) })
        rejected(firstLayer(root) { JsonObject(it + ("sr" to JsonPrimitive(2))) })
    }

    @Test fun missingFieldsAndDeepInputReturnExplicitRejected() {
        rejected(JsonObject(original() - "fr"))
        val deep = "[".repeat(49) + "0" + "]".repeat(49)
        assertTrue(assertIs<BrandMotionDecodeResult.Rejected>(
            BrandMotionDecoder.parseRestrictedJson(deep.toByteArray(), animation.asset.pngFileName),
        ).reason.contains("nesting"))
    }

    @Test fun invalidMaskAndNonIncreasingKeyframesRejectBeforeSampling() {
        val original = original()
        assertIs<BrandMotionDecodeResult.Decoded>(BrandMotionDecoder.parseRestrictedJson(
            original.toString().toByteArray(), animation.asset.pngFileName,
        ))
        val layers = original.getValue("layers").jsonArray.toMutableList()
        val maskedIndex = layers.indexOfFirst { !it.jsonObject["masksProperties"]?.jsonArray.isNullOrEmpty() }
        assertTrue(maskedIndex >= 0, "The original fixture must contain a real mask")
        val layer = layers[maskedIndex].jsonObject
        val masks = layer.getValue("masksProperties").jsonArray.toMutableList()
        masks[0] = JsonObject(masks[0].jsonObject + ("mode" to JsonPrimitive("i")))
        layers[maskedIndex] = JsonObject(layer + ("masksProperties" to JsonArray(masks)))
        val root = JsonObject(original + ("layers" to JsonArray(layers)))
        assertTrue(rejected(root).reason.contains("mask", ignoreCase = true))
        val changed = firstLayer(original()) { layer ->
            val ks = layer.getValue("ks").jsonObject
            val animation = buildJsonObject {
                put("a", 1)
                put("k", JsonArray(listOf(
                    buildJsonObject {
                        put("t", 1); put("s", JsonArray(listOf(JsonPrimitive(0)))); put("e", JsonArray(listOf(JsonPrimitive(1))))
                        val handle = buildJsonObject { put("x", 0.5); put("y", 0.5) }
                        put("i", handle); put("o", handle)
                    },
                    buildJsonObject { put("t", 1); put("s", JsonArray(listOf(JsonPrimitive(1)))) },
                )))
            }
            JsonObject(layer + ("ks" to JsonObject(ks + ("r" to animation))))
        }
        assertTrue(rejected(changed).reason.contains("Non-increasing"))
    }
}
