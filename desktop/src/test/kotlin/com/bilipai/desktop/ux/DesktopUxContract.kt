package com.bilipai.desktop.ux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loader for the shared desktop UX contract. The resource is a byte-identical copy of
 * desktop/tools/ux/desktop-ux-contract.json (the Python parity suite asserts the equality), so a number that drifts
 * between the Python model, the contract and the Kotlin policies fails in one of the two test runs.
 */
internal object DesktopUxContract {
    private const val RESOURCE = "/desktop-ux-contract.json"

    val document: JsonObject by lazy {
        val stream = requireNotNull(DesktopUxContract::class.java.getResourceAsStream(RESOURCE)) {
            "Missing test resource $RESOURCE"
        }
        Json.parseToJsonElement(stream.use { it.readBytes() }.toString(Charsets.UTF_8)).jsonObject
    }

    fun section(name: String): JsonObject = document.getValue(name).jsonObject

    fun objects(array: JsonElement): List<JsonObject> = array.jsonArray.map { it.jsonObject }

    fun strings(array: JsonElement): List<String> = array.jsonArray.map { it.jsonPrimitive.content }

    fun string(source: JsonObject, key: String): String = source.getValue(key).jsonPrimitive.content

    fun int(source: JsonObject, key: String): Int = source.getValue(key).jsonPrimitive.int

    fun double(source: JsonObject, key: String): Double = source.getValue(key).jsonPrimitive.double

    fun ints(array: JsonElement): List<Int> = (array as JsonArray).map { it.jsonPrimitive.int }
}
