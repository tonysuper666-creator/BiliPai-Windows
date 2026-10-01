/** Diagnostic only: initialize the actual product's existing lazy WinRT API and retain full causes. */
public final class WinRtLoadProbe {
    public static void main(String[] args) throws Exception {
        String name = "com.bilipai.desktop.player.WindowsMediaSession$WinRt";
        Class<?> type = Class.forName(name);
        System.out.println("actualOrigin=" + type.getProtectionDomain().getCodeSource().getLocation());
        try {
            var field = type.getDeclaredField("Companion");
            field.setAccessible(true);
            Object companion = field.get(null);
            var getApi = companion.getClass().getDeclaredMethod("getApi");
            getApi.setAccessible(true);
            Object api = getApi.invoke(companion);
            System.out.println("actualLazyWinRtLoad=PASS apiClass=" + api.getClass().getName());
        } catch (Throwable failure) {
            System.out.println("actualLazyWinRtLoad=FAIL");
            failure.printStackTrace(System.out);
            Throwable cause = failure;
            for (int i = 0; cause != null && i < 12; i++, cause = cause.getCause()) {
                System.out.println("cause[" + i + "]=" + cause.getClass().getName() + ":" + cause.getMessage());
            }
        }
    }
}
