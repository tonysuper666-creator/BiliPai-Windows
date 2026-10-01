package com.bilipai.desktop.rootfixture;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.nio.file.*;
import java.util.*;
import javax.swing.SwingUtilities;

/** Own-process startup observation only. It opens no account/login/share action. */
public final class RootStartupFixture {
    public static void main(String[] args) throws Exception {
        Path report=Path.of(args[0]);Files.createDirectories(report);
        java.util.Timer timer=new java.util.Timer("owned-root-observer",true);
        timer.schedule(new TimerTask(){public void run(){try{
            SwingUtilities.invokeAndWait(()->{try{
                Window[] windows=Window.getWindows();Frame root=null;
                for(Window window:windows)if(window instanceof Frame frame && frame.isShowing() && frame.getTitle().contains("BiliPai"))root=frame;
                if(root==null)throw new IllegalStateException("Actual Main root window was not shown");
                Rectangle bounds=new Rectangle(root.getLocationOnScreen(),root.getSize());
                String codeSource=RootStartupFixture.class.getClassLoader().loadClass("com.bilipai.desktop.MainKt").getProtectionDomain().getCodeSource().getLocation().toString();
                String result="{\"windowShown\":true,\"rootWidth\":"+bounds.width+",\"rootHeight\":"+bounds.height+",\"actualMainCodeSource\":\""+codeSource.replace("\\","\\\\").replace("\"","\\\"")+"\",\"rootPageInteractionAccepted\":false,\"visualAcceptance\":false,\"accountActions\":0,\"mode\":\"live isolated guest startup\",\"closedByOriginalWindowHandler\":true}";
                Files.writeString(report.resolve("observation.json"),result);
                root.dispatchEvent(new WindowEvent(root,WindowEvent.WINDOW_CLOSING));
            }catch(Exception failure){try{Files.writeString(report.resolve("observer-failure.txt"),failure.toString());}catch(Exception ignored){}failure.printStackTrace();for(Window window:Window.getWindows())window.dispose();}});
        }catch(Exception failure){failure.printStackTrace();}}},15_000L);
        Class<?> main=Class.forName("com.bilipai.desktop.MainKt");
        main.getMethod("main",String[].class).invoke(null,(Object)new String[0]);
        timer.cancel();
    }
}
