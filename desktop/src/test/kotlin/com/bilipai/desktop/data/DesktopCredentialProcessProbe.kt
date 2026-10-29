package com.bilipai.desktop.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.file.Path

/** Child process for DesktopCredentialPersistenceTest: one JVM writes or reads the session file and exits, so the
 * round trip goes through the disk and the platform protection, not through memory. */
fun main(args: Array<String>) {
    val (action, file) = args
    val store = DesktopSessionStore(Path.of(file))
    when (action) {
        "write" -> store.saveAccount(mapOf("SESSDATA" to args[2], "bili_jct" to "probe-csrf", "DedeUserID" to args[3]),
            AccountSummary(args[3].toLong(), "probe", ""))
        "logout" -> store.logout()
        "read" -> {
            val sent = store.loadForRequest("https://api.bilibili.com/x/web-interface/nav".toHttpUrl())
                .firstOrNull { it.name == "SESSDATA" }?.value
            println("protection=${DesktopCredentialCipher.protectionId}")
            println("account=${store.account.value?.mid}")
            println("sessdata=$sent")
            println("restoreFailure=${store.credentialRestoreFailure}")
        }
        else -> error("unknown action $action")
    }
}
