/** Headless diagnostic only. No COM event is injected and no UI/media actor is created. */
public final class ActualCallbackRegistrationProbe {
    private static void failure(String label, Throwable failure) {
        System.out.println(label + "=FAIL");
        failure.printStackTrace(System.out);
    }
    public static void main(String[] args) throws Exception {
        Class<?> winRt = Class.forName("com.bilipai.desktop.player.WindowsMediaSession$WinRt");
        var field = winRt.getDeclaredField("Companion"); field.setAccessible(true);
        Object companion = field.get(null);
        var getApi = companion.getClass().getDeclaredMethod("getApi"); getApi.setAccessible(true);
        Object api = getApi.invoke(companion);
        var roInitialize = winRt.getDeclaredMethod("RoInitialize", int.class); roInitialize.setAccessible(true);
        var roUninitialize = winRt.getDeclaredMethod("RoUninitialize"); roUninitialize.setAccessible(true);
        boolean initialized = false;
        try {
            int hr = ((Number) roInitialize.invoke(api, 1)).intValue();
            System.out.println("actualRoInitialize=" + Integer.toHexString(hr));
            initialized = hr >= 0;
        } catch (Throwable failure) { failure("actualRoInitialize", failure); }
        try {
            Class<?> fn = Class.forName("kotlin.jvm.functions.Function1");
            Object unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
            Object action = java.lang.reflect.Proxy.newProxyInstance(fn.getClassLoader(), new Class<?>[]{fn}, (proxy, method, values) -> unit);
            Class<?> delegate = Class.forName("com.bilipai.desktop.player.WindowsMediaSession$ComDelegate");
            var constructor = delegate.getDeclaredConstructor(String.class, fn); constructor.setAccessible(true);
            Object value = constructor.newInstance("0557e996-7b23-5bae-aa81-ea0d671143a4", action);
            System.out.println("actualComDelegateRegistration=PASS class=" + value.getClass().getName());
        } catch (Throwable failure) { failure("actualComDelegateRegistration", failure); }
        if (initialized) roUninitialize.invoke(api);
    }
}
