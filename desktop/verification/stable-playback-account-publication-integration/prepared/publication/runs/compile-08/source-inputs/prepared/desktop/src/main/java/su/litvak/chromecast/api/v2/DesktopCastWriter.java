package su.litvak.chromecast.api.v2;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Sole Channel writer. Enqueue/start admission only; I/O/flush/ACK waits never run inside admission. */
public final class DesktopCastWriter implements AutoCloseable {
    @FunctionalInterface public interface Write { void run() throws IOException; }
    private final Object gate=new Object();
    private volatile Thread ownedThread;
    private boolean closing;
    private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),task->{Thread thread=new Thread(task,"cast-writer");thread.setDaemon(true);ownedThread=thread;return thread;});
    public void write(Write command,BooleanSupplier stillOwned,DesktopCastPublication.Frame frame) throws IOException {
        FutureTask<Void> task=new FutureTask<>(()->{
            Runnable start=()->{synchronized(gate){if(closing||!stillOwned.getAsBoolean())throw new CancellationException("Cast channel closed");}};
            if(frame==null)start.run();else frame.admit(start);
            if(!stillOwned.getAsBoolean()||frame!=null&&!frame.isCurrent())throw new IOException("Cast publication retired");
            // Accepted start is in-flight; selection after this point cannot recall emitted bytes.
            command.run();return null;
        });
        Runnable enqueue=()->{synchronized(gate){
            if(closing||!stillOwned.getAsBoolean())throw new CancellationException("Cast channel closed");writer.execute(task);
        }};
        try{
            if(frame==null)enqueue.run();else frame.admit(enqueue);
            task.get(); // no Store/Channel monitor is held during write/flush/completion wait
        }catch(InterruptedException failure){task.cancel(true);Thread.currentThread().interrupt();throw new IOException("Cast publication interrupted",failure);
        }catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof IOException io)throw io;throw new IOException("Cast publication rejected",cause);
        }catch(RuntimeException failure){task.cancel(true);throw new IOException("Cast publication rejected",failure);}
    }
    public void cancelPending(){synchronized(gate){closing=true;for(Runnable task:writer.shutdownNow())if(task instanceof Future<?> future)future.cancel(false);}}
    @Override public void close() throws IOException{
        cancelPending();if(Thread.currentThread()==ownedThread)return;
        try{if(!writer.awaitTermination(1500,TimeUnit.MILLISECONDS))throw new IOException("Cast writer shutdown timed out");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException("Cast writer shutdown interrupted",failure);}
    }
}
