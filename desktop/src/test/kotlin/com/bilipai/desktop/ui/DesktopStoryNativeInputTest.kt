package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.player.PlayerKeyAction
import com.bilipai.desktop.data.DesktopStoryOwner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Canvas
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Ownership/focus tests use undisplayed components; genuine Canvas inputs have a separate Robot proof. */
class DesktopStoryNativeInputTest {
    private class Fixture : AutoCloseable {
        val surface = JPanel().apply { setSize(200, 400) }
        val canvas = Canvas().also { surface.add(it); it.setSize(200, 400) }
        val outside = JPanel()
        var current: DesktopStoryNativeInputToken? = DesktopStoryNativeInputToken(DesktopStoryOwner("story-a", 7), 11)
        var active = true
        var accepts = true
        val steps = mutableListOf<Pair<Int, DesktopStoryNativeInputToken>>()
        val actions = mutableListOf<Pair<PlayerKeyAction, DesktopStoryNativeInputToken>>()
        val bridge = DesktopStoryNativeInputBridge(surface, { current },
            { direction, token -> if (accepts) { steps += direction to token; true } else false },
            { action, token -> if (accepts) { actions += action to token; true } else false }, isWindowActive = { active })
        fun mouse(id: Int, x: Int = 30, y: Int = 300, button: Int = MouseEvent.BUTTON1, outside: Boolean = false): MouseEvent {
            val event = MouseEvent(if (outside) this.outside else canvas, id, 0, 0, x, y, 1, false, button)
            bridge.mouse(event); return event
        }
        fun press() = mouse(MouseEvent.MOUSE_PRESSED)
        fun up() = mouse(MouseEvent.MOUSE_RELEASED, y = 100)
        fun key(code: Int = KeyEvent.VK_SPACE, id: Int = KeyEvent.KEY_PRESSED, modifiers: Int = 0,
            location: Int = KeyEvent.KEY_LOCATION_STANDARD): Boolean = bridge.key(KeyEvent(canvas, id, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED, location))
        override fun close() = bridge.close()
    }
    private fun onEdt(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }

    @Test fun verticalDragCommitsOnlyAtReleaseAndOnce(): Unit = onEdt {
        Fixture().use { f ->
            f.press(); f.mouse(MouseEvent.MOUSE_DRAGGED, y=100, button=MouseEvent.NOBUTTON)
            assertTrue(f.steps.isEmpty()); assertTrue(f.up().isConsumed)
            f.up(); assertEquals(listOf(1), f.steps.map { it.first }); assertEquals(11L, f.steps.single().second.sourceVersion)
        }
    }
    @Test fun downwardDragUsesSamePagerStepContract(): Unit = onEdt {
        Fixture().use { f -> f.mouse(MouseEvent.MOUSE_PRESSED,y=100); f.mouse(MouseEvent.MOUSE_RELEASED,y=300)
            assertEquals(listOf(-1),f.steps.map { it.first }) }
    }
    @Test fun smallHorizontalAndNonPrimaryDragsDoNotSwitch(): Unit = onEdt {
        Fixture().use { f ->
            f.press(); f.mouse(MouseEvent.MOUSE_RELEASED,y=275)
            f.press(); f.mouse(MouseEvent.MOUSE_RELEASED,x=190,y=240)
            f.mouse(MouseEvent.MOUSE_PRESSED,button=MouseEvent.BUTTON3); f.up()
            assertTrue(f.steps.isEmpty());assertFalse(f.key())
        }
    }
    @Test fun changedNativeSourceDuringDragCannotCommit(): Unit = onEdt {
        Fixture().use { f -> f.press(); f.current=f.current!!.copy(sourceVersion=12); assertFalse(f.up().isConsumed); assertTrue(f.steps.isEmpty()) }
    }
    @Test fun changedAccountEpochDuringDragCannotCommit(): Unit = onEdt {
        Fixture().use { f -> f.press(); f.current=f.current!!.copy(owner=f.current!!.owner.copy(sessionEpoch=8)); f.up()
            assertTrue(f.steps.isEmpty());assertFalse(f.key()) }
    }
    @Test fun foreignRouteOrNativeTakeoverCannotReuseInputLease(): Unit = onEdt {
        Fixture().use { f -> f.press(); f.current=f.current!!.copy(owner=DesktopStoryOwner("foreign",7)); f.up()
            assertFalse(f.key());assertTrue(f.steps.isEmpty()); f.current=null;f.press(); assertFalse(f.key()); assertTrue(f.actions.isEmpty()) }
    }
    @Test fun spaceUsesOriginalPolicyAndSuppressesKeyRepeatUntilRelease(): Unit = onEdt {
        Fixture().use { f -> f.press(); assertTrue(f.key());assertTrue(f.key());assertTrue(f.key(id=KeyEvent.KEY_RELEASED))
            assertTrue(f.key());assertEquals(listOf(PlayerKeyAction.PlayPause,PlayerKeyAction.PlayPause),f.actions.map { it.first }) }
    }
    @Test fun pageKeysAndArrowsUsePagerWithoutPlayerVolumeActions(): Unit = onEdt {
        Fixture().use { f -> f.press()
            for(code in listOf(KeyEvent.VK_DOWN,KeyEvent.VK_PAGE_DOWN,KeyEvent.VK_UP,KeyEvent.VK_PAGE_UP)) {
                assertTrue(f.key(code));f.key(code,KeyEvent.KEY_RELEASED)
            }
            assertEquals(listOf(1,1,-1,-1),f.steps.map { it.first });assertTrue(f.actions.isEmpty()) }
    }
    @Test fun systemAndShiftShortcutsKeepOriginalPolicy(): Unit = onEdt {
        Fixture().use { f -> f.press()
            for(mod in listOf(InputEvent.CTRL_DOWN_MASK,InputEvent.ALT_DOWN_MASK,InputEvent.META_DOWN_MASK)) assertFalse(f.key(modifiers=mod))
            assertFalse(f.key(KeyEvent.VK_PAGE_DOWN,modifiers=InputEvent.SHIFT_DOWN_MASK))
            assertFalse(f.key(KeyEvent.VK_DOWN,modifiers=InputEvent.SHIFT_DOWN_MASK))
            assertTrue(f.key(KeyEvent.VK_RIGHT,modifiers=InputEvent.SHIFT_DOWN_MASK))
            assertEquals(PlayerKeyAction.SeekRelative(10_000),f.actions.single().first);assertTrue(f.steps.isEmpty()) }
    }
    @Test fun textControlClickAndTabReturnKeyboardToCompose(): Unit = onEdt {
        Fixture().use { f -> f.press();f.mouse(MouseEvent.MOUSE_PRESSED,outside=true);assertFalse(f.key())
            f.press();assertFalse(f.key(KeyEvent.VK_TAB));assertFalse(f.key());assertTrue(f.actions.isEmpty()) }
    }
    @Test fun inactiveWindowCannotSwitchOrPause(): Unit = onEdt {
        Fixture().use { f -> f.press();f.active=false; f.up();assertFalse(f.key());assertTrue(f.steps.isEmpty());assertTrue(f.actions.isEmpty()) }
    }
    @Test fun staleReleaseDoesNotConsumeForeignOwnersKey(): Unit = onEdt {
        Fixture().use { f -> f.press();assertTrue(f.key());f.current=null;assertFalse(f.key(id=KeyEvent.KEY_RELEASED)) }
    }
    @Test fun nextSourceWithSameOwnedRouteCanUseNewKeyboardToken(): Unit = onEdt {
        Fixture().use { f -> f.press();f.current=f.current!!.copy(sourceVersion=20);assertTrue(f.key())
            assertEquals(20L,f.actions.single().second.sourceVersion) }
    }
    @Test fun keypadLocationIsPreservedForOriginalKeyboardPolicy(): Unit = onEdt {
        Fixture().use { f -> f.press();assertTrue(f.key(KeyEvent.VK_NUMPAD1,location=KeyEvent.KEY_LOCATION_NUMPAD))
            assertEquals(PlayerKeyAction.SeekPercent(0.1f),f.actions.single().first) }
    }
    @Test fun rejectedNavigationDoesNotConsumeAndDoesNotArmRepeat(): Unit = onEdt {
        Fixture().use { f -> f.press();f.accepts=false;assertFalse(f.up().isConsumed);assertFalse(f.key(KeyEvent.VK_DOWN))
            f.accepts=true;assertTrue(f.key(KeyEvent.VK_DOWN));assertEquals(1,f.steps.size) }
    }
    @Test fun typedCharacterIsConsumedOnlyForOwnedHandledKey(): Unit = onEdt {
        Fixture().use { f ->
            val typed=KeyEvent(f.canvas,KeyEvent.KEY_TYPED,0,0,KeyEvent.VK_UNDEFINED,' ')
            f.press();assertFalse(f.bridge.key(typed));assertTrue(f.key());assertTrue(f.bridge.key(typed))
            f.current=null;assertFalse(f.bridge.key(typed))
        }
    }
    @Test fun nativeFocusCallbackRequiresOwnedActivePointer(): Unit = onEdt {
        val f=Fixture();f.close();var focus=0
        DesktopStoryNativeInputBridge(f.surface,{ f.current },{ _,_ ->false },{ _,_ ->false },
            onNativeFocus={focus++},isWindowActive={f.active}).use { bridge ->
            val press=MouseEvent(f.canvas,MouseEvent.MOUSE_PRESSED,0,0,20,30,1,false,MouseEvent.BUTTON1)
            f.current=null;bridge.mouse(press);assertEquals(0,focus)
            f.current=DesktopStoryNativeInputToken(DesktopStoryOwner("owned",7),11);bridge.mouse(press);assertEquals(1,focus)
        }
    }
    @Test fun disposeRemovesAwtListenerAndMakesFurtherEventsInert(): Unit = onEdt {
        val count=Toolkit.getDefaultToolkit().awtEventListeners.size;val f=Fixture()
        assertEquals(count+1,Toolkit.getDefaultToolkit().awtEventListeners.size)
        f.press();f.close();f.close();f.up();assertFalse(f.key());assertTrue(f.actions.isEmpty());assertTrue(f.steps.isEmpty())
        assertEquals(count,Toolkit.getDefaultToolkit().awtEventListeners.size)
    }
}
