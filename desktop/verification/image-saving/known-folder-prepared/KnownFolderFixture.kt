package com.bilipai.desktop.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Path

private val expectedGuid = "3081e2331e4e7646835a98395c3bc3bb"
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

private class FakeKnownFolder(
    val com: Int = 0,
    val shell: Int = 0,
    val path: String? = "D:\\移动图片\\B站 保存",
    val failRead: Boolean = false,
    val throwAfterOutput: Boolean = false,
) : DesktopWindowsKnownFolderNative {
    var initialized = 0; var uninitialized = 0; var queries = 0; var freed = 0
    var guid = ""
    var allocation: Memory? = null
    override fun initializeCom(reserved: Pointer?, flags: Int): Int {
        check(reserved == null && flags == 0); initialized++; return com
    }
    override fun uninitializeCom() { uninitialized++ }
    override fun getKnownFolderPath(folderId: Pointer, flags: Int, token: Pointer?, output: PointerByReference): Int {
        check(flags == 0 && token == null); queries++
        guid = folderId.getByteArray(0, 16).hex(); check(guid == expectedGuid)
        if (path != null) {
            allocation = Memory((path.length + 1L) * Native.WCHAR_SIZE)
            allocation!!.setWideString(0, path); output.value = allocation
        }
        if (throwAfterOutput) error("task-owned native failure")
        return shell
    }
    override fun readWideString(pointer: Pointer): String {
        check(pointer == allocation)
        if (failRead) error("task-owned decode failure")
        return pointer.getWideString(0)
    }
    override fun freeTaskMemory(pointer: Pointer) {
        check(pointer == allocation && freed == 0); freed++; allocation!!.close()
    }
    fun resolve() = DesktopWindowsImageSaveDirectory.resolveDefault(true) { this }
}

private fun fakeProof(): List<String> {
    val passed = mutableListOf<String>()
    fun case(name: String, block: () -> Unit) { block(); passed += name }
    case("redirected Unicode Pictures path, exact GUID flags0 NULL token and no normalization") {
        val api = FakeKnownFolder()
        check(api.resolve().getOrThrow() == Path.of("D:\\移动图片\\B站 保存\\BiliPai"))
        check(api.queries == 1 && api.freed == 1 && api.uninitialized == 1)
    }
    case("S_FALSE owns a COM reference and balances it") {
        val api = FakeKnownFolder(com = 1); check(api.resolve().isSuccess)
        check(api.uninitialized == 1 && api.freed == 1)
    }
    case("existing different COM apartment is preserved") {
        val api = FakeKnownFolder(com = DesktopWindowsImageSaveDirectory.RPC_E_CHANGED_MODE)
        check(api.resolve().isSuccess); check(api.uninitialized == 0 && api.freed == 1)
    }
    case("failed COM initialization does not query or uninitialize") {
        val api = FakeKnownFolder(com = 0x80004005.toInt())
        val failure = api.resolve().exceptionOrNull() as DesktopKnownFolderFailure
        check(failure.operation == "CoInitializeEx" && failure.hresult == api.com)
        check(api.queries == 0 && api.freed == 0 && api.uninitialized == 0)
    }
    case("failed Shell query releases nonnull output and returns HRESULT without fake folder") {
        val api = FakeKnownFolder(shell = 0x80070002.toInt())
        val failure = api.resolve().exceptionOrNull() as DesktopKnownFolderFailure
        check(failure.operation == "SHGetKnownFolderPath" && failure.hresult == api.shell)
        check(!failure.message.orEmpty().contains("移动图片"))
        check(api.freed == 1 && api.uninitialized == 1)
    }
    case("success with NULL output is a failure") {
        val api = FakeKnownFolder(path = null); check(api.resolve().isFailure)
        check(api.freed == 0 && api.uninitialized == 1)
    }
    case("empty native output is a failure with cleanup") {
        val api = FakeKnownFolder(path = ""); check(api.resolve().isFailure)
        check(api.freed == 1 && api.uninitialized == 1)
    }
    case("relative native output never becomes USERPROFILE or working-directory fallback") {
        val api = FakeKnownFolder(path = "Pictures"); check(api.resolve().isFailure)
        check(api.freed == 1 && api.uninitialized == 1)
    }
    case("wide-string decoding failure releases output and COM reference") {
        val api = FakeKnownFolder(failRead = true); check(api.resolve().isFailure)
        check(api.freed == 1 && api.uninitialized == 1)
    }
    case("exception after out pointer assignment still releases it") {
        val api = FakeKnownFolder(throwAfterOutput = true); check(api.resolve().isFailure)
        check(api.freed == 1 && api.uninitialized == 1)
    }
    case("non-Windows resolves failure without loading DLL") {
        var called = false
        check(DesktopWindowsImageSaveDirectory.resolveDefault(false) { called = true; FakeKnownFolder() }.isFailure)
        check(!called)
    }
    case("native binding failure reports no path and propagates no fake default") {
        val failure = DesktopWindowsImageSaveDirectory.resolveDefault(true) { throw UnsatisfiedLinkError("test") }
            .exceptionOrNull() as DesktopKnownFolderFailure
        check(failure.operation == "native-binding")
    }
    return passed
}

private class TrackingKnownFolder : DesktopWindowsKnownFolderNative {
    val delegate = JnaWindowsKnownFolderNative
    var initHr: Int? = null; var queryHr: Int? = null
    var queryCalls = 0; var freed = 0; var uninitialized = 0; var guid = ""
    override fun initializeCom(reserved: Pointer?, flags: Int): Int = delegate.initializeCom(reserved, flags).also { initHr = it }
    override fun uninitializeCom() { uninitialized++; delegate.uninitializeCom() }
    override fun getKnownFolderPath(folderId: Pointer, flags: Int, token: Pointer?, output: PointerByReference): Int {
        check(flags == 0 && token == null); guid = folderId.getByteArray(0, 16).hex()
        check(guid == expectedGuid); queryCalls++
        return delegate.getKnownFolderPath(folderId, flags, token, output).also { queryHr = it }
    }
    override fun readWideString(pointer: Pointer) = delegate.readWideString(pointer)
    override fun freeTaskMemory(pointer: Pointer) { freed++; delegate.freeTaskMemory(pointer) }
}

private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
private fun nativeProof(): String {
    check(Native.POINTER_SIZE == 8 && Native.WCHAR_SIZE == 2)
    val reports = mutableListOf<String>(); var firstPath: Path? = null
    fun query(name: String, expectedInit: Int?, expectedUninit: Int) {
        val api = TrackingKnownFolder()
        val path = DesktopWindowsImageSaveDirectory.resolveDefault(true) { api }.getOrThrow()
        check(path.isAbsolute && path.fileName.toString() == "BiliPai")
        if (firstPath == null) firstPath = path else check(path == firstPath)
        if (expectedInit != null) check(api.initHr == expectedInit)
        check(api.queryHr == 0 && api.queryCalls == 1 && api.freed == 1 && api.uninitialized == expectedUninit)
        reports += "{\"case\":${q(name)},\"initializeHresult\":${api.initHr},\"shellHresult\":${api.queryHr},\"freeCalls\":${api.freed},\"ownedComUninitializeCalls\":${api.uninitialized},\"guidBytes\":${q(api.guid)},\"passed\":true}"
    }
    query("fresh calling thread", 0, 1)
    query("repeated call balances fresh COM reference", 0, 1)
    val outerMta = JnaWindowsKnownFolderNative.initializeCom(null, 0)
    check(outerMta >= 0)
    try { query("already MTA S_FALSE reference balanced", 1, 1) }
    finally { JnaWindowsKnownFolderNative.uninitializeCom() }
    val outerSta = JnaWindowsKnownFolderNative.initializeCom(null, 2)
    check(outerSta >= 0)
    try { query("already STA different apartment preserved", DesktopWindowsImageSaveDirectory.RPC_E_CHANGED_MODE, 0) }
    finally { JnaWindowsKnownFolderNative.uninitializeCom() }
    return "{\"actualNative\":true,\"os\":${q(System.getProperty("os.name"))},\"architecture\":${q(System.getProperty("os.arch"))},\"pointerSize\":${Native.POINTER_SIZE},\"wcharSize\":${Native.WCHAR_SIZE},\"resolvedDefault\":${q(firstPath.toString())},\"folderFlags\":0,\"nullToken\":true,\"directoryOrFileWrites\":0,\"cases\":[${reports.joinToString(",")}],\"mainIntegrated\":false,\"persistentUiIntegrated\":false}"
}

fun main(args: Array<String>) {
    when (args.single()) {
        "fake" -> println("{\"actualNative\":false,\"cases\":[${fakeProof().joinToString(",") { q(it) }}],\"passed\":true}")
        "native" -> println(nativeProof())
        else -> error("unknown fixture mode")
    }
}
