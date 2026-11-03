// Diagnostic only: append to DesktopOriginalMessagePagesTest.kt in a disposable
// worktree, run this class, then restore that file before the acceptance run.
// All VM/admission/repository/HTTP code is real. Only one state setter is paused
// after the VM computes its next value, to force a legal Default-pool interleaving.
class Ton1170ChatInterleavingProbe {
    @Test fun delayedControlSnapshotOverwritesCompletedSendOnDefault(): Unit = runBlocking {
        val f = MessagePageTestFixture()
        val allowHttp = CountDownLatch(1)
        val allowPublication = CountDownLatch(1)
        val controlEntered = CompletableDeferred<Unit>()
        val snapshot = CompletableDeferred<ChatUiState>()
        val intercepted = AtomicBoolean(false)
        try {
            f.respond = { hit ->
                if (hit.path.endsWith("get_session_ss")) {
                    controlEntered.complete(Unit)
                    check(allowHttp.await(5, TimeUnit.SECONDS))
                }
                f.default(hit)
            }
            val vm = ChatViewModel(7, 1, f.admission)
            val latestField = vm.javaClass.getDeclaredField("latestMessagesJob").apply { isAccessible = true }
            withTimeout(5_000) { (latestField.get(vm) as Job).join(); controlEntered.await() }
            check(vm.uiState.value.messagesLoaded && vm.uiState.value.isSessionControlLoading)
            val stateField = vm.javaClass.getDeclaredField("_uiState").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val original = stateField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ChatUiState>
            stateField.set(vm, object : kotlinx.coroutines.flow.MutableStateFlow<ChatUiState> by original {
                override var value: ChatUiState
                    get() = original.value
                    set(next) {
                        if (!next.isSessionControlLoading && intercepted.compareAndSet(false, true)) {
                            println("CONTROL_SNAPSHOT thread=${Thread.currentThread().name} sentText=${next.sentText} isSending=${next.isSending}")
                            snapshot.complete(next)
                            check(allowPublication.await(5, TimeUnit.SECONDS))
                        }
                        original.value = next
                    }
            })
            allowHttp.countDown()
            val stale = withTimeout(5_000) { snapshot.await() }
            check(stale.sentText == null && !stale.isSending)
            vm.sendMessage("probe acknowledgement")
            f.waitFor { vm.uiState.value.sentText == "probe acknowledgement" &&
                !vm.uiState.value.isSending && !f.admission.hasPendingEditorMutations() }
            println("SEND_COMPLETED sentText=${vm.uiState.value.sentText} isSending=${vm.uiState.value.isSending}")
            allowPublication.countDown()
            val operations = f.admission.javaClass.getDeclaredField("operations").apply { isAccessible = true }
            val control = (operations.get(f.admission) as Map<*, *>)["chat-control"] as Job
            withTimeout(5_000) { control.join() }
            check(!control.isCancelled && !vm.uiState.value.isSessionControlLoading)
            check(vm.uiState.value.sentText == null && !vm.uiState.value.isSending)
            check(f.hits.count { it.path.endsWith("send_msg") } == 1)
            println("REPRODUCED_STALE_CONTROL_OVERWRITE sentText=${vm.uiState.value.sentText} isSending=${vm.uiState.value.isSending} sendRequests=1")
        } finally {
            allowHttp.countDown(); allowPublication.countDown()
            f.shutdown()
        }
    }
}
