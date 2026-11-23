package raft04;
import java.util.concurrent.*;
public final class ClientMonitor {
    private final EventLog log;
    public ClientMonitor(String id){log=new EventLog("monitor-"+id+".txt");}
    public void event(String text){log.event(text);}
    public void received(int k,Batch b){
        event("RECEBIDO;p"+k+";termo="+b.term+";lider=n"+b.leader+";hb="+b.hb+";ultimoSeq="+b.lastSeq);
    }
    public static void schedule(HeartbeatMonitor... monitors){
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"monitores");t.setDaemon(true);return t;});
        timer.scheduleAtFixedRate(()->{
            try {for(HeartbeatMonitor m:monitors)m.check();}
            catch(RuntimeException e){e.printStackTrace();System.exit(1);}
        },Wire.VERIFICACAO_MS,Wire.VERIFICACAO_MS,TimeUnit.MILLISECONDS);
    }
}
