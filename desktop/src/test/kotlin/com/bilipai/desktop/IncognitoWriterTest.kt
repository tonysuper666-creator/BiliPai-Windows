package com.bilipai.desktop.privacyfixture

import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.audio.ListenAudioSaved
import com.bilipai.desktop.audio.ListenAudioStore
import com.bilipai.desktop.data.*
import com.bilipai.desktop.settings.DesktopSettingsSearchRepository
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.android.purebilibili.feature.video.player.PlaylistItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.*
import kotlin.test.*

class IncognitoWriterTest {
    private fun root() = Files.createTempDirectory("bilipai-incognito-fixture-")
    private fun card(id: String) = VideoCard(id, "Fixture $id", "", "Fixture creator", 1, 120)
    private fun history(path: Path) = Json.parseToJsonElement(Files.readString(path.resolve("library.json"))).jsonObject["history"]

    @Test fun realDiskHistorySuppressedRestoredAndExistingEntriesKept(): Unit = runBlocking {
        val global=root();val privacy=DesktopSearchPreferences(global)
        val secondFacade=DesktopSearchPreferences(global)
        val account=global.resolve("accounts/7");val library=DesktopLibrary(account, privacy::isPrivacyModeEnabledSync)
        library.record(card("BV-existing"));library.checkpoint("BV-existing", 11, 0, 21.0)
        val file=account.resolve("library.json");val before=Files.readAllBytes(file)
        secondFacade.setPrivacyMode(true)
        // This existing facade's old StateFlow does not decide write permission.
        assertFalse(privacy.privacyMode.value);assertTrue(privacy.isPrivacyModeEnabledSync())
        library.record(card("BV-private"));library.checkpoint("BV-existing", 11, 0, 88.0)
        assertContentEquals(before, Files.readAllBytes(file))
        val restart=DesktopLibrary(account, secondFacade::isPrivacyModeEnabledSync)
        assertEquals(listOf("BV-existing"),restart.history().map{it.bvid})
        assertEquals(21,restart.resumeCard("BV-existing")?.progressSeconds)
        assertNull(restart.resumeCard("BV-private"))
        secondFacade.setPrivacyMode(false)
        library.record(card("BV-public"));library.checkpoint("BV-public", 22, 1, 45.0)
        val restored=DesktopLibrary(account, privacy::isPrivacyModeEnabledSync)
        assertEquals(listOf("BV-public","BV-existing"),restored.history().map{it.bvid})
        assertEquals(45,restored.resumeCard("BV-public")?.progressSeconds)
        assertEquals(22L,restored.resumeCard("BV-public")?.preferredCid)
        assertEquals(1,restored.resumeCard("BV-public")?.pageIndex)
    }

    @Test fun delayedOrdinaryAndStoryWriterCallbacksReadOneCurrentGlobalSwitch(): Unit = runBlocking {
        val global=root();val privacy=DesktopSearchPreferences(global)
        val account=global.resolve("accounts/1");val library=DesktopLibrary(account, privacy::isPrivacyModeEnabledSync)
        library.record(card("BV-preserved"))
        val capturedOrdinary={library.record(card("BV-ordinary"))}
        // Story queues route through the same Controller/library write boundary.
        val capturedStory={library.record(card("BV-story"))}
        privacy.setPrivacyMode(true)
        capturedOrdinary();capturedStory()
        assertEquals(listOf("BV-preserved"),DesktopLibrary(account, privacy::isPrivacyModeEnabledSync).history().map{it.bvid})
        privacy.setPrivacyMode(false);capturedStory()
        assertEquals(listOf("BV-story","BV-preserved"),DesktopLibrary(account, privacy::isPrivacyModeEnabledSync).history().map{it.bvid})
        assertFalse(DesktopSearchPreferences.readPrivacyModeEnabledSync(global))
    }

    @Test fun privateHistoryDoesNotDisableExplicitFavoritesThemeOrExistingReadAccess(): Unit = runBlocking {
        val global=root();val privacy=DesktopSearchPreferences(global);val directory=global.resolve("library")
        val library=DesktopLibrary(directory, privacy::isPrivacyModeEnabledSync)
        library.record(card("BV-kept"));val originalHistory=history(directory)
        privacy.setPrivacyMode(true)
        library.toggleFavorite(card("BV-favorite"));library.setDark(true);library.setAutomaticUpdates(false)
        val restart=DesktopLibrary(directory, privacy::isPrivacyModeEnabledSync)
        assertEquals(originalHistory,history(directory));assertEquals(listOf("BV-kept"),restart.history().map{it.bvid})
        assertTrue(restart.isFavorite("BV-favorite"));assertTrue(restart.dark);assertFalse(restart.automaticUpdates)
        assertEquals(listOf("BV-favorite"),restart.favorites().map{it.bvid})
    }

    @Test fun corruptedSwitchCannotTurnIntoSuccessfulHistoryWrite(): Unit = runBlocking {
        val global=root();val privacy=DesktopSearchPreferences(global);val directory=global.resolve("library")
        val library=DesktopLibrary(directory, privacy::isPrivacyModeEnabledSync);library.record(card("BV-before"))
        privacy.setPrivacyMode(false)
        val file=directory.resolve("library.json");val before=Files.readAllBytes(file)
        Files.writeString(global.resolve("search/plugin-settings.json"),"{broken fixture")
        assertFails{library.record(card("BV-leaked"))}
        assertFails{library.checkpoint("BV-before",33,0,45.0)}
        assertContentEquals(before,Files.readAllBytes(file))
        assertEquals(listOf("BV-before"),library.history().map{it.bvid})
    }

    @Test fun cloudOrdinaryAndPgcUseSameOriginalSuccessfulPrivacyNoopAndEpochGuard(): Unit = runBlocking {
        val global=root();val privacy=DesktopSearchPreferences(global);privacy.setPrivacyMode(true)
        var sends=0;var credentials=0;var notifications=0;var epoch=8L
        suspend fun report(type:Int) = reportDesktopPlaybackHeartbeat(privacy::isPrivacyModeEnabledSync,8L,{epoch},
            {credentials++;7L},{credentials++;"fixture-csrf"},"BV-fixture",123L,17L,13L,100L,
            aid=1L,epid=if(type==4)456L else 0L,sid=if(type==4)789L else 0L,videoType=type,subType=if(type==4)1 else null,
            onReported={notifications++}) { fields ->
                sends++;assertEquals(type.toString(),fields["type"]);if(type==4){assertEquals("456",fields["epid"]);assertEquals("789",fields["sid"])};0
            }
        assertTrue(report(3));assertTrue(report(4));assertEquals(0,sends);assertEquals(0,credentials);assertEquals(0,notifications)
        privacy.setPrivacyMode(false);assertTrue(report(3));assertTrue(report(4));assertEquals(2,sends);assertEquals(2,notifications)
        epoch=9L;assertFalse(report(4));assertEquals(2,sends)
    }

    @Test fun originalListenResumeAndRecentStoreRemainUsableInPrivacyMode(): Unit = runBlocking {
        val global=root();val privacy=DesktopSearchPreferences(global);privacy.setPrivacyMode(true)
        val file=global.resolve("account/listen-state.json");val store=ListenAudioStore(file)
        val item=PlaylistItem(bvid="BV-listen-fixture",cid=99,title="Fixture audio",cover="",owner="Fixture creator")
        store.save(ListenAudioSaved(queue=listOf(item),currentIndex=0,recent=listOf(item),positionSeconds=27.0))
        val restored=ListenAudioStore(file).read()
        assertEquals(listOf(item),restored.queue);assertEquals(listOf(item),restored.recent)
        assertEquals(27.0,restored.positionSeconds);assertTrue(privacy.isPrivacyModeEnabledSync())
    }

    @Test fun ordinarySearchAndOriginalSettingsHistoryObserveSameDiskSwitch(): Unit = runBlocking {
        val global=root();val ordinary=DesktopSearchPreferences(global)
        val controllerFacade=DesktopSearchPreferences(global)
        val context=DesktopPluginContext(DesktopPluginStore(global.resolve("global-plugin-store")))
        val settings=DesktopSettingsSearchRepository(context,ordinary::isPrivacyModeEnabledSync)
        ordinary.record(7L,"kept video search");settings.record("kept settings search")
        controllerFacade.setPrivacyMode(true)
        val beforeOrdinary=ordinary.history(7L).value;val beforeSettings=settings.history.first()
        val ordinaryFile=global.resolve("accounts/7/search/plugin-settings.json")
        val settingsFile=context.store.root.resolve("plugin-settings.json")
        val ordinaryBytes=Files.readAllBytes(ordinaryFile);val settingsBytes=Files.readAllBytes(settingsFile)
        ordinary.record(7L,"private video search");settings.record("private settings search")
        assertEquals(beforeOrdinary,ordinary.history(7L).value);assertEquals(beforeSettings,settings.history.first())
        assertContentEquals(ordinaryBytes,Files.readAllBytes(ordinaryFile));assertContentEquals(settingsBytes,Files.readAllBytes(settingsFile))
        controllerFacade.setPrivacyMode(false)
        ordinary.record(7L,"resumed video search");settings.record("resumed settings search")
        assertEquals(listOf("resumed video search","kept video search"),ordinary.history(7L).value.map{it.keyword})
        assertEquals(listOf("resumed settings search","kept settings search"),settings.history.first())
    }
}
