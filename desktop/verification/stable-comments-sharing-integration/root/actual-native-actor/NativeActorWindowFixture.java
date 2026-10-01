package com.bilipai.desktop.rootfixture;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.Frame;
import java.awt.Label;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.jvm.functions.Function0;

/** Invokes the exact compiled product actor and real DLL with its own HWND.
 * No target is chosen, no message is sent, and no screen pixels are read.
 */
public final class NativeActorWindowFixture {
    public interface Telemetry extends StdCallLibrary {
        int BilipaiShareState(long token, IntByReference state);
        int BilipaiShareOwnerState(long token, IntByReference owner, IntByReference execution,
                                  IntByReference bound, IntByReference released);
        int BilipaiShareDispatchStats(IntByReference registered, IntByReference unregistered,
                                     IntByReference completed, IntByReference timedOut,
                                     IntByReference destroyed, IntByReference live);
    }
    private static Object callSuspend(Object actor, Method method, Object... args) throws Exception {
        CompletableFuture<Object> future = new CompletableFuture<>();
        Continuation<Object> done = new Continuation<>() {
            public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
            public void resumeWith(Object result) {
                try { ResultKt.throwOnFailure(result); future.complete(result); }
                catch (Throwable failure) { future.completeExceptionally(failure); }
            }
        };
        Object[] actual = java.util.Arrays.copyOf(args, args.length+1);
        actual[args.length] = done;
        Object value = method.invoke(actor, actual);
        if (value != IntrinsicsKt.getCOROUTINE_SUSPENDED()) future.complete(value);
        return future.get(12, TimeUnit.SECONDS);
    }
    private static Object fieldOnEdt(Object actor, Field field) throws Exception {
        AtomicReference<Object> value = new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try {value.set(field.get(actor));}
            catch (Exception failure) {throw new RuntimeException(failure);}});
        return value.get();
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path report = Path.of(args[0]);
        Path dll = Path.of(args[1]);
        Frame frame = new Frame("BiliPai Windows 原生分享检查");
        AtomicBoolean owns = new AtomicBoolean(true);
        Object actor = null;
        int stateSeen = -1;
        boolean shown = false, retired = false;
        int registered = -1, unregistered = -1, live = -1;
        try {
            SwingUtilities.invokeAndWait(()->{
                frame.add(new Label("正在检查系统分享接口；无需选择分享对象。"));
                frame.setSize(470, 130);
                frame.setLocationByPlatform(true);
                frame.setVisible(true);
            });
            Class<?> type = Class.forName("com.bilipai.desktop.diagnostics.DesktopNativeTextShare");
            String expected = (String) Class.forName("com.bilipai.desktop.diagnostics.DesktopNativeDiagnosticShareAssetHash")
                .getField("sha256").get(null);
            actor = type.getConstructor(Function0.class, String.class, Function0.class)
                .newInstance((Function0<Path>)()->dll, expected, (Function0<Frame>)()->frame);
            Method share = type.getMethod("share", String.class, String.class, Function0.class, Continuation.class);
            Method shutdown = type.getMethod("shutdown", Continuation.class);
            Field active = type.getDeclaredField("active"); active.setAccessible(true);
            Object result = callSuspend(actor, share, "BiliPai 分享检查", "BiliPai Windows 原生分享接口本地检查", (Function0<Boolean>)owns::get);
            shown = Boolean.TRUE.equals(result);
            check(shown, "Product actor did not accept real WinRT Show for its own visible HWND");
            Long token = (Long)fieldOnEdt(actor, active);
            check(token != null && token != 0L, "Actor did not retain its actual native session");
            Telemetry telemetry = Native.load(dll.toString(), Telemetry.class);
            IntByReference owner = new IntByReference(), execution = new IntByReference();
            IntByReference bound = new IntByReference(), released = new IntByReference();
            check(telemetry.BilipaiShareOwnerState(token, owner, execution, bound, released) >= 0,
                "Read-only native owner telemetry failed");
            check(owner.getValue() != 0 && owner.getValue() == execution.getValue() && bound.getValue() == 1,
                "Native Bind/Show did not execute on its actual HWND owner thread");
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime() < deadline) {
                IntByReference state = new IntByReference();
                if (telemetry.BilipaiShareState(token, state) < 0) break;
                stateSeen = state.getValue();
                if (stateSeen >= 2) break;
                Thread.sleep(100);
            }
            check(stateSeen >= 1 && stateSeen <= 4, "Native share session entered failure/unexpected state: "+stateSeen);
            owns.set(false);
            deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime() < deadline && fieldOnEdt(actor, active) != null) Thread.sleep(50);
            retired = fieldOnEdt(actor, active) == null;
            check(retired, "Real actor watcher did not retire its changed page owner");
            IntByReference state = new IntByReference();
            check(telemetry.BilipaiShareState(token, state) < 0, "Retired real native token remained registered");
            callSuspend(actor, shutdown);
            IntByReference reg = new IntByReference(), unreg = new IntByReference(), done = new IntByReference();
            IntByReference timeout = new IntByReference(), destroyed = new IntByReference(), current = new IntByReference();
            check(telemetry.BilipaiShareDispatchStats(reg,unreg,done,timeout,destroyed,current) >= 0, "Native dispatch stats failed");
            registered=reg.getValue(); unregistered=unreg.getValue(); live=current.getValue();
            check(registered == unregistered && live == 0 && timeout.getValue() == 0,
                "Real owner dispatcher did not fully detach");
            String codeSource = type.getProtectionDomain().getCodeSource().getLocation().toString();
            Files.writeString(report.resolve("observation.json"),
                "{\"passed\":true,\"actorCodeSource\":\""+codeSource.replace("\\","\\\\")+"\","
                +"\"actualNativeShowAccepted\":"+shown+",\"nativeStateSeen\":"+stateSeen+","
                +"\"dataRequestedProvidedObserved\":"+(stateSeen==2)+",\"ownerRetired\":"+retired+","
                +"\"registered\":"+registered+",\"unregistered\":"+unregistered+",\"liveDispatchers\":"+live+","
                +"\"externalTargetChosen\":false,\"externalMessageSent\":false,\"screenCapture\":false,"
                +"\"actualRootShareButtonAccepted\":false,\"visualSharePaneAccepted\":false}");
        } finally {
            owns.set(false);
            if (actor != null) try { callSuspend(actor, actor.getClass().getMethod("shutdown", Continuation.class)); }
                catch (Exception failure) { failure.printStackTrace(); }
            SwingUtilities.invokeAndWait(frame::dispose);
        }
        System.out.println("ACTUAL_PRODUCT_NATIVE_SHARE_OWN_WINDOW_PASS");
        System.exit(0);
    }
}
