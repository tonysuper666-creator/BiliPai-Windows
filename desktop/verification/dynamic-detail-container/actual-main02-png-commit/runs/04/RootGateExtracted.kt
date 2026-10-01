package com.bilipai.desktop.ui.pngproof
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel

internal fun rootOwned(page:FixturePageOwner):Boolean = with(page) { alive.get()&&exportOwner.epoch==capturedEpoch&&cardSession.isOwned()&&repository.sessionEpoch==capturedEpoch }
internal fun rootCommit(page:FixturePageOwner,commit:()->Unit):Boolean = with(page) {
exportGuard.withCurrentDynamicCacheOwner(exportOwner){
                    synchronized(exportOwnerLock){
                        if(!owned())throw CancellationException("Reply export page retired")
                        commit()
                    }
                }
}
internal fun rootDispose(page:FixturePageOwner) = with(page) {
synchronized(exportOwnerLock){alive.set(false)};replySession.close();pageScope.cancel()
}
