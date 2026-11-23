package raft04;
import java.util.function.Consumer;
public final class HeartbeatMonitor {
    public enum State { DESCONHECIDO, ATIVO, SUSPEITO }
    private final int k;
    private final long timeout;
    private final Consumer<String> log;
    private long term=-1,hb=0,last=System.nanoTime();
    private int leader;
    private State state=State.DESCONHECIDO;
    public HeartbeatMonitor(int k,long period,int factor,Consumer<String> log){
        this.k=k;timeout=Math.multiplyExact(Math.multiplyExact(period,factor),1000000L);this.log=log;
    }
    public synchronized boolean onHeartbeat(long t,int l,long h){
        Wire.member(l);
        if(t<1||h<1) throw new IllegalArgumentException("Heartbeat invalido");
        if(t<term || (t==term && (l!=leader || h<=hb))) return false;
        if(t>term) log.accept("NOVO_LIDER;p"+k+";termo="+t+";lider=n"+l);
        else if(h>hb+1) log.accept("HB_PERDIDO;p"+k+";esperado="+(hb+1)+";recebido="+h);
        if(state==State.SUSPEITO) log.accept("RETOMA;p"+k+";termo="+t);
        term=t;leader=l;hb=h;last=System.nanoTime();state=State.ATIVO;
        return true;
    }
    public synchronized void check(){
        if(state!=State.SUSPEITO && System.nanoTime()-last>timeout){
            state=State.SUSPEITO;log.accept("SUSPEITA;p"+k+";termo="+term);
        }
    }
    public synchronized State state(){return state;}
}
