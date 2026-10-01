package su.litvak.chromecast.api.v2;
/** Caller admission only; not another session authority. */
public final class DesktopCastPublication {
    public interface Frame { void admit(Runnable publication); boolean isCurrent(); }
    private static final ThreadLocal<Frame> current = new ThreadLocal<>();
    public static Frame current() { return current.get(); }
    public static Frame replace(Frame frame) {
        Frame previous=current.get();if(frame==null)current.remove();else current.set(frame);return previous;
    }
    private DesktopCastPublication() {}
}
