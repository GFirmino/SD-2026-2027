from pathlib import Path

ROOT = Path(r'C:\Projects\SDT\SD-2026-2027\Sprints\E1-COMINDIR05')
BASE = ROOT.parent / 'E1-COMINDIR04'
for project in ('no', 'produtor', 'processador'):
    dest = ROOT / ('raft04-' + project) / 'src' / 'raft04'
    dest.mkdir(parents=True, exist_ok=True)
    for name in ('VectorClock', 'Partitioner', 'IpfsPubSub'):
        text = (BASE / 'rep04-lider' / 'src' / 'rep04' / (name+'.java')).read_text(encoding='utf-8')
        (dest / (name+'.java')).write_text(text.replace('rep04', 'raft04'), encoding='utf-8')

def write(name, source, projects=('no', 'produtor', 'processador')):
    for project in projects:
        (ROOT / ('raft04-'+project) / 'src' / 'raft04' / (name+'.java')).write_text(source.strip()+'\n',encoding='utf-8')

write('Wire', r'''
package raft04;

import java.nio.file.Files;
import java.nio.file.Paths;

public final class Wire {
    public static final int DEFAULT_N = 3, GRUPO = 3, VERIFICACAO_MS = 200;
    public static final int PORTO_BASE = Integer.getInteger("raft04.portoBase", 7000);
    public static final int T_TCP_MS = 5000;
    public static final double LIMIAR_ALARME = 28.0;
    public static final long PERIODO_HB_MS = Long.getLong("raft04.periodo", 2000L);
    public static final int FATOR_TIMEOUT = Integer.getInteger("raft04.fator", 3);
    public static final long ELEICAO_MIN_MS = Long.getLong("raft04.eleicaoMin", 3 * PERIODO_HB_MS);
    public static final long ELEICAO_MAX_MS = Long.getLong("raft04.eleicaoMax", 6 * PERIODO_HB_MS);
    public static final String API = System.getProperty("raft04.api", "http://127.0.0.1:5001/api/v0");
    static {
        if (PERIODO_HB_MS < 1 || FATOR_TIMEOUT < 1 || ELEICAO_MIN_MS < 1
                || ELEICAO_MAX_MS < ELEICAO_MIN_MS || ELEICAO_MAX_MS > Long.MAX_VALUE / 1000000L - 1)
            throw new IllegalArgumentException("Temporizacoes invalidas");
    }
    private Wire() { }
    public static String topicIn(int k) { return "raft04-in-p" + k; }
    public static String topicData(int k) { return "raft04-p" + k; }
    public static String topicElection(int k) { return "raft04-eleicao-p" + k; }
    public static int nodePort(int k, int i) {
        int port = PORTO_BASE + k * GRUPO + i - 1;
        if (k < 0 || i < 1 || i > GRUPO || port < 1 || port > 65535)
            throw new IllegalArgumentException("No/porto invalido");
        return port;
    }
    public static boolean isolated(int k, int i) { return Files.exists(Paths.get("isolar-p"+k+"-n"+i)); }
    public static void member(int i) {
        if (i < 1 || i > GRUPO) throw new IllegalArgumentException("Membro invalido: " + i);
    }
    public static void validateReading(String sensor, double temp) {
        if (sensor == null || sensor.isEmpty() || sensor.indexOf(';') >= 0
                || sensor.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || !Double.isFinite(temp)) throw new IllegalArgumentException("Leitura invalida");
    }
    public static String in(String sensor, double temp, long l, VectorClock v) {
        validateReading(sensor, temp);
        if (l < 0) throw new IllegalArgumentException("Lamport negativo");
        return "IN;"+sensor+";"+temp+";"+l+";"+v;
    }
    public static String[] inParts(String msg) {
        String[] f = msg.split(";", -1);
        if (f.length != 5 || !f[0].equals("IN")) throw new IllegalArgumentException("IN invalido");
        return new String[] {f[1], f[2], f[3], f[4]};
    }
}
''')

write('SensorRecord', r'''
package raft04;

import java.time.LocalDateTime;

public final class SensorRecord {
    private final long seq, term, lamport;
    private final String sensorId, timestamp;
    private final double temperature;
    private final VectorClock vector;
    public SensorRecord(long seq, long term, String sensor, double temp, String time, long l, VectorClock v) {
        Wire.validateReading(sensor, temp);
        if (seq < 1 || term < 1 || l < 0) throw new IllegalArgumentException("seq/termo/L invalido");
        LocalDateTime.parse(time);
        this.seq=seq; this.term=term; sensorId=sensor; temperature=temp; timestamp=time;
        lamport=l; vector=v.copy();
    }
    public static SensorRecord now(long seq, long term, String sensor, double temp, long l, VectorClock v) {
        return new SensorRecord(seq,term,sensor,temp,LocalDateTime.now().toString(),l,v);
    }
    public String toLine() { return seq+";"+term+";"+sensorId+";"+temperature+";"+timestamp+";"+lamport+";"+vector; }
    public static SensorRecord fromLine(String line, int n) {
        String[] f=line.split(";",-1);
        if(f.length!=7) throw new IllegalArgumentException("Registo invalido");
        return new SensorRecord(Long.parseLong(f[0]),Long.parseLong(f[1]),f[2],Double.parseDouble(f[3]),
                f[4],Long.parseLong(f[5]),VectorClock.parse(f[6],n));
    }
    public long getSeq(){return seq;}
    public long getTerm(){return term;}
    public long getLamport(){return lamport;}
    public String getSensorId(){return sensorId;}
    public String getTimestamp(){return timestamp;}
    public double getTemperature(){return temperature;}
    public VectorClock getVector(){return vector.copy();}
}
''')

write('Batch', r'''
package raft04;

import java.util.*;

public final class Batch {
    public final long term, hb, lastSeq, lastTerm;
    public final int leader, port, count;
    public final String address;
    public final List<SensorRecord> records;
    public Batch(long t,int leader,String address,int port,long hb,long seq,long lastTerm,List<SensorRecord> rs) {
        this(t,leader,address,port,hb,seq,lastTerm,rs.size(),rs);
    }
    private Batch(long t,int leader,String address,int port,long hb,long seq,long lastTerm,int count,List<SensorRecord> rs) {
        Wire.member(leader);
        if(t<1 || address.isBlank() || address.matches(".*[;\\s].*") || port<1 || port>65535 || hb<1
                || seq<0 || lastTerm<0 || lastTerm>t || (seq==0)!=(lastTerm==0) || count<0 || count>seq)
            throw new IllegalArgumentException("Cabecalho LOTE invalido");
        term=t; this.leader=leader; this.address=address; this.port=port; this.hb=hb;
        lastSeq=seq; this.lastTerm=lastTerm; this.count=count; records=List.copyOf(rs);
    }
    public String encode() {
        StringBuilder s=new StringBuilder("LOTE;"+term+";"+leader+";"+address+";"+port+";"+hb+";"+lastSeq+";"+lastTerm+";"+count);
        for(SensorRecord r:records) s.append('\n').append(r.toLine());
        return s.toString();
    }
    public static Batch header(String msg) {
        String[] f=msg.split("\\R",2)[0].split(";",-1);
        if(f.length!=9 || !f[0].equals("LOTE")) throw new IllegalArgumentException("LOTE invalido");
        return new Batch(Long.parseLong(f[1]),Integer.parseInt(f[2]),f[3],Integer.parseInt(f[4]),
                Long.parseLong(f[5]),Long.parseLong(f[6]),Long.parseLong(f[7]),Integer.parseInt(f[8]),List.of());
    }
    public static Batch decode(String msg,int n) {
        Batch h=header(msg);
        String[] lines=msg.split("\\R",-1);
        if(lines.length!=h.count+1) throw new IllegalArgumentException("Quantidade do lote incorreta");
        List<SensorRecord> rs=new ArrayList<>(); long prev=0, prevTerm=0;
        for(int j=1;j<lines.length;j++) {
            SensorRecord r=SensorRecord.fromLine(lines[j],n);
            if(r.getSeq()<=prev || r.getSeq()>h.lastSeq || r.getTerm()>h.term || r.getTerm()<prevTerm
                    || (r.getSeq()==h.lastSeq && r.getTerm()!=h.lastTerm))
                throw new IllegalArgumentException("Sequencia/termo do lote invalido");
            rs.add(r); prev=r.getSeq(); prevTerm=r.getTerm();
        }
        return new Batch(h.term,h.leader,h.address,h.port,h.hb,h.lastSeq,h.lastTerm,rs);
    }
}
''')

write('VoteRequest', r'''
package raft04;
public final class VoteRequest {
    public final long term,lastSeq,lastTerm;
    public final int candidate;
    public VoteRequest(long t,int c,long seq,long lt){
        Wire.member(c);
        if(t<1 || seq<0 || lt<0 || lt>t || (seq==0)!=(lt==0)) throw new IllegalArgumentException("Pedido invalido");
        term=t;candidate=c;lastSeq=seq;lastTerm=lt;
    }
    public String encode(){return "PEDIDO_VOTO;"+term+";"+candidate+";"+lastSeq+";"+lastTerm;}
    public static VoteRequest decode(String s){
        String[] f=s.split(";",-1);
        if(f.length!=5 || !f[0].equals("PEDIDO_VOTO")) throw new IllegalArgumentException("Pedido invalido");
        return new VoteRequest(Long.parseLong(f[1]),Integer.parseInt(f[2]),Long.parseLong(f[3]),Long.parseLong(f[4]));
    }
}
''',('no',))

write('VoteReply', r'''
package raft04;
public final class VoteReply {
    public final long term;
    public final int voter,candidate;
    public final boolean granted;
    public VoteReply(long t,int v,int c,boolean g){
        Wire.member(v);Wire.member(c);
        if(t<0) throw new IllegalArgumentException("Termo negativo");
        term=t;voter=v;candidate=c;granted=g;
    }
    public String encode(){return "VOTO;"+term+";"+voter+";"+candidate+";"+(granted?"S":"N");}
    public static VoteReply decode(String s){
        String[] f=s.split(";",-1);
        if(f.length!=5 || !f[0].equals("VOTO") || !(f[4].equals("S")||f[4].equals("N")))
            throw new IllegalArgumentException("Voto invalido");
        return new VoteReply(Long.parseLong(f[1]),Integer.parseInt(f[2]),Integer.parseInt(f[3]),f[4].equals("S"));
    }
}
''',('no',))

write('EventLog', r'''
package raft04;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
public final class EventLog {
    private final Path path;
    public EventLog(String name){path=Paths.get(name);}
    public synchronized void event(String text){
        String line=System.currentTimeMillis()+";"+text;
        try { Files.writeString(path,line+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
        catch(IOException e){throw new UncheckedIOException(e);}
        System.out.println(line);
    }
}
''')

write('HeartbeatMonitor', r'''
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
''',('produtor','processador'))

print('Classes partilhadas preparadas em',ROOT)

# Conservar os comandos/rajadas da COMINDIR04; adaptar os observadores aos termos.
src=(BASE/'rep04-produtor/src/rep04/Producer.java').read_text(encoding='utf-8').replace('rep04','raft04')
src=src.replace('static final String DEFAULT_API = "http://127.0.0.1:5001/api/v0";', 'static final String DEFAULT_API = Wire.API;')
src=src.replace('MonitorLog','ClientMonitor')
src=src.replace('monitors[part].onHeartbeat(b.inc, b.hb);','monitors[part].onHeartbeat(b.term, b.leader, b.hb);')
src=src.replace('pubSub.publish(Wire.topicIn(part), msg);','pubSub.publish(Wire.topicIn(part), msg);\n                        sent.event("p"+part+";"+sensor+";"+temp);')
src=src.replace('pubSub.publish(Wire.topicIn(part), message);','pubSub.publish(Wire.topicIn(part), message);\n                sent.event("p"+part+";"+sensor+";"+temp);')
src=src.replace('ClientMonitor log = new ClientMonitor("produtor");','ClientMonitor log = new ClientMonitor("produtor");\n        EventLog sent = new EventLog("produtor.txt");')
src=src.replace('AVISO: lider p','AVISO: particao p').replace(' suspeito; leitura ',' sem lider conhecido; leitura ')
write('Producer',src,('produtor',))
src=(BASE/'rep04-processador/src/rep04/AlarmProcessor.java').read_text(encoding='utf-8').replace('rep04','raft04').replace('MonitorLog','ClientMonitor')
src=src.replace('List<Set<Long>>','List<Set<String>>')
src=src.replace('monitors[k].onHeartbeat(batch.inc, batch.hb);','if (!monitors[k].onHeartbeat(batch.term, batch.leader, batch.hb)) return;')
src=src.replace('onRecord(k, r);','onRecord(k, r);')
src=src.replace('seen.get(k).add(record.getSeq())','seen.get(k).add(record.getSeq()+":"+record.getTerm())')
src=src.replace('"http://127.0.0.1:5001/api/v0"','Wire.API')
src=src.replace('AVISO: lider p','AVISO: particao p').replace(' suspeito; alarme ',' sem lider conhecido; alarme ')
write('AlarmProcessor',src,('processador',))
write('ClientMonitor',r'''
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
''',('produtor','processador'))
