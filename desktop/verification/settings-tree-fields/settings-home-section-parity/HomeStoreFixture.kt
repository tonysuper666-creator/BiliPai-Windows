package com.android.purebilibili.feature.settings

import com.android.purebilibili.core.store.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.*

fun main(args:Array<String>):Unit = runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    val checks=mutableListOf<String>()
    suspend fun case(name:String,block:suspend ()->Unit){block();checks+=name}
    val root=Files.createTempDirectory("bilipai-original-home-fields-")
    val preferences=DesktopDiscoveryPreferences(root)
    val repository=DesktopRepository(DesktopSessionStore(root.resolve("test-session.json"),persistent=false))
    val discovery=DesktopDiscoveryRepository(repository,preferences)
    val options=resolveFeedApiSegmentOptions()
    case("original option values and descriptions are the same enum consumed by the actual repository") {
        check(options.map{it.value}==DesktopRecommendationMode.entries)
        check(options.map{it.label}==DesktopRecommendationMode.entries.map{it.label})
        check(discovery.feedMode.value==DesktopRecommendationMode.WEB)
        check(discovery.refreshCount.value==DEFAULT_HOME_REFRESH_COUNT)
    }
    case("actual shared repository write notifies its existing flow and survives a fresh reader") {
        val target=DesktopRecommendationMode.entries.first{it!=discovery.feedMode.value}
        val waiting=async(start=CoroutineStart.UNDISPATCHED){discovery.feedMode.first{it==target}}
        discovery.setFeedMode(target)
        check(withTimeout(1500){waiting.await()}==target)
        check(DesktopDiscoveryPreferences(root).feedMode.value==target)
        val disk=Json.parseToJsonElement(Files.readString(root.resolve("discovery/plugin-settings.json"))).jsonObject
        check(disk["feed_api"]!!.jsonObject["type"]!!.jsonPrimitive.int==target.value)
    }
    case("original slider summary range and steps preserve normalization and actual persisted request count") {
        check(resolveHomeRefreshSliderRange()==MIN_HOME_REFRESH_COUNT.toFloat()..MAX_HOME_REFRESH_COUNT.toFloat())
        check(resolveHomeRefreshSliderSteps()==(MAX_HOME_REFRESH_COUNT-MIN_HOME_REFRESH_COUNT-1).coerceAtLeast(0))
        discovery.setRefreshCount(MAX_HOME_REFRESH_COUNT+100)
        check(discovery.refreshCount.value==MAX_HOME_REFRESH_COUNT)
        check(DesktopDiscoveryPreferences(root).refreshCount.value==MAX_HOME_REFRESH_COUNT)
        discovery.setRefreshCount(MIN_HOME_REFRESH_COUNT-100)
        check(discovery.refreshCount.value==MIN_HOME_REFRESH_COUNT)
        check(resolveHomeRefreshCountSummary(discovery.refreshCount.value).contains(MIN_HOME_REFRESH_COUNT.toString()))
    }
    case("failed actual atomic persistence leaves the existing field unchanged") {
        val failureRoot=Files.createTempDirectory("bilipai-home-disk-failure-")
        val failurePreferences=DesktopDiscoveryPreferences(failureRoot)
        val initial=failurePreferences.feedMode.value
        Files.createDirectories(failureRoot.resolve("discovery/plugin-settings.json"))
        val target=DesktopRecommendationMode.entries.first{it!=initial}
        var threw=false
        try {failurePreferences.setFeedMode(target)}catch(expected:Exception){threw=true}
        check(threw)
        check(failurePreferences.feedMode.value==initial)
    }
    Files.writeString(output.resolve("result.json"),buildJsonObject {
        put("passed",true);put("checks",JsonArray(checks.map(::JsonPrimitive)))
        put("actualWindowsDiscoveryStoreAndRepository",true);put("accountOrNetworkRequestMade",false)
        put("nativeWindowCreated",false);put("uiClickClaimed",false);put("dynamicFieldsClaimed",false)
    }.toString())
    println("Original Home fields: ${checks.size} actual repository/flow/disk cases passed, no account HTTP/window.")
    Unit
}
