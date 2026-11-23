package raft04;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Um processo por (particao,no). Ordem de locks: no -> RaftState -> ficheiro/log. */
public final class PartitionNode {
    private final int k,i,n;
    private final Partitioner partitioner;
    private final IpfsPubSub pub=new IpfsPubSub(Wire.API);
    private final EventLog log;
    private final RecordFile file;
    private final RaftState raft;
    private final ScheduledExecutorService timer=Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> heartbeat;
    private long seq,lamport,hb,receivedTerm=-1,receivedHb;
    private VectorClock vector;
    private List<SensorRecord> pending=new ArrayList<>();

    public PartitionNode(int k,int i,int n) throws IOException {
        if(n!=Wire.DEFAULT_N || k<0 || k>=n) throw new IllegalArgumentException("Usar N=3 e 0<=k<N");
        Wire.member(i); this.k=k;this.i=i;this.n=n;partitioner=new Partitioner(n);
        log=new EventLog("eventos-p"+k+"-n"+i+".txt");
        file=new RecordFile(Path.of("no-p"+k+"-n"+i+".txt"),n);
        raft=new RaftState(k,i,Wire.GRUPO,Wire.ELEICAO_MIN_MS,Wire.ELEICAO_MAX_MS,
                Path.of("estado-p"+k+"-n"+i+".txt"),log::event);
        restore();
        log.event("TERMO;p"+k+";termo="+raft.term()+";arranque=sim;no=n"+i);
    }
    private void restore() {
        SensorRecord last=file.last();
        seq=last==null?0:last.getSeq();lamport=last==null?0:last.getLamport();
        vector=last==null?new VectorClock(n):last.getVector();
        if(vector.get(k)!=seq) throw new IllegalStateException("Historico com V[k] != seq");
    }
    private long lastTerm(){SensorRecord r=file.last();return r==null?0:r.getTerm();}
    private void publish(String topic,String msg,boolean control) {
        if(control && Wire.isolated(k,i)) return;
        try { pub.publish(topic,msg); }
        catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(IOException e){log.event("ERRO;p"+k+";publicacao="+e.getMessage());}
    }
    private void stopHeartbeatIfFollower() {
        if(raft.role()!=RaftState.Role.LIDER && heartbeat!=null) {
            heartbeat.cancel(false); heartbeat=null; pending.clear();
        }
    }
    private void becomeLeader() {
        restore(); lamport=0; vector=new VectorClock(n); hb=0;pending.clear();
        if(heartbeat!=null) heartbeat.cancel(false);
        heartbeat=timer.scheduleAtFixedRate(()->guard(this::sendBatch),0,Wire.PERIODO_HB_MS,TimeUnit.MILLISECONDS);
    }
    private void electionTick() {
        String request=null;
        synchronized(this) {
            long t=raft.tick();stopHeartbeatIfFollower();
            if(t>=0) request=new VoteRequest(t,i,seq,lastTerm()).encode();
        }
        if(request!=null) publish(Wire.topicElection(k),request,true);
    }
    private void electionMessage(String msg) {
        if(Wire.isolated(k,i)) return;
        String reply=null;
        synchronized(this) {
            if(msg.startsWith("PEDIDO_VOTO;")) {
                VoteRequest r=VoteRequest.decode(msg);
                if(r.candidate==i) return;
                RaftState.Vote v=raft.onVoteRequest(r.term,r.candidate,r.lastSeq,r.lastTerm,seq,lastTerm());
                reply=new VoteReply(v.term,i,r.candidate,v.granted).encode();
            } else {
                VoteReply r=VoteReply.decode(msg);
                if(r.voter==i) return;
                if(raft.onVote(r.term,r.voter,r.candidate,r.granted)) becomeLeader();
            }
            stopHeartbeatIfFollower();
        }
        if(reply!=null) publish(Wire.topicElection(k),reply,true);
    }
    private void input(String msg) throws IOException {
        String[] f=Wire.inParts(msg); String sensor=f[0];double temp=Double.parseDouble(f[1]);
        Wire.validateReading(sensor,temp);
        long receivedL=Long.parseLong(f[2]);VectorClock receivedV=VectorClock.parse(f[3],n);
        if(receivedL<0 || partitioner.of(sensor)!=k) throw new IllegalArgumentException("Entrada/particao invalida");
        synchronized(this) {
            long t=raft.termIfLeader();
            if(t<0) {log.event("IGNORADO;p"+k+";IN;termo="+raft.term());return;}
            long nextSeq=Math.addExact(seq,1),nextL=Math.addExact(Math.max(lamport,receivedL),1);
            VectorClock nextV=vector.copy();nextV.merge(receivedV);nextV.tick(k);
            if(nextV.get(k)!=nextSeq) throw new IllegalArgumentException("V[k] != seq (conhecimento de historico descartado)");
            SensorRecord r=SensorRecord.now(nextSeq,t,sensor,temp,nextL,nextV);
            file.append(r);seq=nextSeq;lamport=nextL;vector=nextV;pending.add(r);
            System.out.println("EVENTO: seq="+seq+" termo="+t+" L="+lamport+" V="+vector);
        }
    }
    private void sendBatch() throws IOException,InterruptedException {
        Batch b;
        synchronized(this) {
            long t=raft.termIfLeader();
            if(t<0){stopHeartbeatIfFollower();return;}
            b=new Batch(t,i,"127.0.0.1",Wire.nodePort(k,i),Math.addExact(hb,1),seq,lastTerm(),pending);
            hb=b.hb;pending=new ArrayList<>();
        }
        if(Wire.isolated(k,i)) return;
        String msg=b.encode();pub.publish(Wire.topicData(k),msg);
        String line=b.term+";"+b.hb+";"+System.currentTimeMillis()+";"+b.lastSeq+";"+b.lastTerm+";"+b.count+";"+msg.getBytes(StandardCharsets.UTF_8).length+"\n";
        Files.writeString(Path.of("hb-p"+k+"-n"+i+".txt"),line,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private static boolean same(SensorRecord a,SensorRecord b){return a.getSeq()==b.getSeq() && a.getTerm()==b.getTerm();}
    private List<SensorRecord> request(Batch b,long from) throws IOException {
        if(Wire.isolated(k,i)) throw new IOException("No isolado");
        if(from>b.lastSeq) return List.of();
        log.event("RECUPERACAO;p"+k+";termo="+b.term+";lider=n"+b.leader+";de="+from+";ate="+b.lastSeq);
        List<SensorRecord> rs=new ArrayList<>();
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress(b.address,b.port),Wire.T_TCP_MS);socket.setSoTimeout(Wire.T_TCP_MS);
            BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            PrintWriter out=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true);
            out.println("PEDIDO "+from+" "+b.lastSeq);
            String line;boolean finished=false;long expected=from;
            while((line=in.readLine())!=null) {
                if(Wire.isolated(k,i)) throw new IOException("No isolado durante recuperacao");
                if(line.equals("FIM")){finished=true;break;}
                if(line.startsWith("ERRO")) throw new IOException(line);
                SensorRecord r=SensorRecord.fromLine(line,n);
                if(r.getSeq()!=expected++ || r.getSeq()>b.lastSeq || r.getTerm()>b.term || partitioner.of(r.getSensorId())!=k)
                    throw new IOException("Resposta TCP fora do intervalo/particao/termo");
                rs.add(r);
            }
            if(!finished || expected!=b.lastSeq+1 || rs.get(rs.size()-1).getTerm()!=b.lastTerm)
                throw new IOException("Resposta TCP incompleta ou historico alterado");
        }
        return rs;
    }
    private void dataMessage(String msg) throws IOException {
        if(Wire.isolated(k,i)) return;
        Batch b=Batch.decode(msg,n);
        if(b.leader==i) return;
        for(SensorRecord r:b.records) if(partitioner.of(r.getSensorId())!=k) throw new IllegalArgumentException("Particao errada");
        List<SensorRecord> local;
        synchronized(this) {
            if(b.term==receivedTerm && b.hb<=receivedHb) return; // duplicados nao renovam o prazo
            if(!raft.onBatch(b.term,b.leader)) {log.event("IGNORADO;p"+k+";LOTE;termo="+b.term);return;}
            stopHeartbeatIfFollower();
            receivedTerm=b.term;receivedHb=b.hb;
            local=file.snapshot();
            log.event("RECEBIDO;p"+k+";termo="+b.term+";lider=n"+b.leader+";hb="+b.hb+";ultimoSeq="+b.lastSeq);
        }
        boolean divergent=local.size()>b.lastSeq
                || (b.lastSeq>0 && local.size()>=b.lastSeq && local.get(Math.toIntExact(b.lastSeq-1)).getTerm()!=b.lastTerm);
        for(SensorRecord r:b.records) if(r.getSeq()<=local.size() && !same(local.get((int)r.getSeq()-1),r)) divergent=true;
        List<SensorRecord> next=new ArrayList<>(local);
        if(divergent) {
            // Recuar bloco a bloco, sem manter o monitor durante operacoes de rede.
            int start=local.size(); List<SensorRecord> tail;
            while(true) {
                long term=local.get(start-1).getTerm();
                while(start>1 && local.get(start-2).getTerm()==term) start--;
                tail=request(b,start);
                if(start==1 || (!tail.isEmpty() && same(local.get(start-1),tail.get(0)))) break;
                start--;
            }
            next=new ArrayList<>(local.subList(0,start-1));next.addAll(tail);
        } else if(local.size()<b.lastSeq) {
            for(SensorRecord r:b.records) if(r.getSeq()==next.size()+1L) next.add(r);
            if(next.size()!=b.lastSeq) { next=new ArrayList<>(local);next.addAll(request(b,local.size()+1L)); }
        }
        synchronized(this) {
            if(Wire.isolated(k,i) || raft.term()!=b.term || raft.leader()!=b.leader || raft.role()!=RaftState.Role.SEGUIDOR) return;
            int common=0;
            while(common<local.size() && common<next.size() && same(local.get(common),next.get(common))) common++;
            if(common<local.size()) {
                List<SensorRecord> removed=local.subList(common,local.size());
                StringJoiner terms=new StringJoiner(","),lines=new StringJoiner("\n");
                for(SensorRecord r:removed){terms.add(Long.toString(r.getTerm()));lines.add(r.toLine());}
                file.replace(next);
                String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(lines.toString().getBytes(StandardCharsets.UTF_8));
                log.event("TRUNCADO;p"+k+";de="+(common+1)+";descartados="+removed.size()+";termos="+terms+";registos="+encoded);
            } else for(int j=local.size();j<next.size();j++) file.append(next.get(j));
            restore();
        }
    }
    private void serve(Socket socket) throws IOException {
        try(Socket s=socket) {
            if(Wire.isolated(k,i)) return;
            s.setSoTimeout(Wire.T_TCP_MS);
            BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
            PrintWriter out=new PrintWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8),true);
            String line=in.readLine();if(line==null)return;
            String[] f=line.trim().split("\\s+");
            if(f.length!=3 || !f[0].equals("PEDIDO")){out.println("ERRO Pedido invalido");return;}
            List<SensorRecord> rs;
            try { synchronized(this){rs=file.range(Long.parseLong(f[1]),Long.parseLong(f[2]));} }
            catch(IllegalArgumentException e){out.println("ERRO Intervalo indisponivel");return;}
            for(SensorRecord r:rs){if(Wire.isolated(k,i))return;out.println(r.toLine());}
            out.println("FIM");
        }
    }
    private interface Action {void run() throws Exception;}
    private void guard(Action a) {
        try{a.run();}
        catch(UncheckedIOException e){e.printStackTrace();System.exit(1);}
        catch(IOException e){log.event("ERRO;p"+k+";io="+e.getMessage());}
        catch(IllegalArgumentException e){log.event("IGNORADO;p"+k+";invalido="+e.getMessage());}
        catch(Exception e){e.printStackTrace();System.exit(1);}
    }
    private void subscribe(String topic,Consumer<String> action) {
        Thread t=new Thread(()->{
            try{new IpfsPubSub(Wire.API).subscribe(topic,action);}
            catch(Exception e){e.printStackTrace();System.exit(1);}
        },topic+"-n"+i);t.setDaemon(true);t.start();
    }
    public void start() throws IOException {
        try(ServerSocket server=new ServerSocket()) {
            server.setReuseAddress(true);server.bind(new InetSocketAddress(Wire.nodePort(k,i)));
            subscribe(Wire.topicIn(k),msg->guard(()->input(msg)));
            subscribe(Wire.topicData(k),msg->guard(()->dataMessage(msg)));
            subscribe(Wire.topicElection(k),msg->guard(()->electionMessage(msg)));
            timer.scheduleAtFixedRate(()->guard(this::electionTick),Wire.VERIFICACAO_MS,Wire.VERIFICACAO_MS,TimeUnit.MILLISECONDS);
            while(true){Socket s=server.accept();guard(()->serve(s));}
        } finally {timer.shutdownNow();}
    }
    public static void main(String[] args) {
        try {
            if(args.length<2 || args.length>3) throw new IllegalArgumentException("PartitionNode <k> <i> [N]");
            new PartitionNode(Integer.parseInt(args[0]),Integer.parseInt(args[1]),args.length==3?Integer.parseInt(args[2]):3).start();
        }catch(Exception e){e.printStackTrace();System.exit(1);}
    }
}
