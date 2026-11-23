package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Estado de eleicao; nunca chama operacoes que adquiram o monitor do no. */
public final class RaftState {
    public enum Role { SEGUIDOR, CANDIDATO, LIDER }
    public static final class Vote {
        public final long term;
        public final boolean granted;
        Vote(long t, boolean g) { term=t; granted=g; }
    }
    private final int partition, self, groupSize;
    private final long minNanos, maxNanos;
    private final Path stateFile;
    private final Consumer<String> log;
    private long term, deadline;
    private int votedFor, leader;
    private Role role=Role.SEGUIDOR;
    private final Set<Integer> votes=new HashSet<>();

    public RaftState(int k,int self,int groupSize,long minMs,long maxMs,Path stateFile,Consumer<String> log) throws IOException {
        Wire.member(self);
        if(groupSize!=Wire.GRUPO || minMs<1 || maxMs<minMs) throw new IllegalArgumentException("Grupo/prazo invalido");
        partition=k; this.self=self; this.groupSize=groupSize; this.stateFile=stateFile; this.log=log;
        minNanos=Math.multiplyExact(minMs,1000000L); maxNanos=Math.multiplyExact(maxMs,1000000L);
        if(Files.exists(stateFile)) {
            String[] f=Files.readString(stateFile,StandardCharsets.UTF_8).trim().split(";",-1);
            if(f.length!=2) throw new IOException("Estado persistente invalido");
            term=Long.parseLong(f[0]); votedFor=Integer.parseInt(f[1]);
            if(term<0 || votedFor<0 || votedFor>groupSize || (term==0 && votedFor!=0))
                throw new IOException("Termo/voto persistido invalido");
        }
        resetTimer();
    }
    private void resetTimer() {
        long delay=maxNanos>minNanos?ThreadLocalRandom.current().nextLong(minNanos,maxNanos+1):minNanos;
        deadline=System.nanoTime()+delay;
    }
    private void persist() {
        try {
            Path tmp=stateFile.resolveSibling(stateFile.getFileName()+".tmp");
            Files.writeString(tmp,term+";"+votedFor+"\n",StandardCharsets.UTF_8);
            Files.move(tmp,stateFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    private void adoptTerm(long t) {
        if(role==Role.LIDER) log.accept("DEMISSAO;p"+partition+";termo="+term+";novo="+t);
        long old=term;
        term=t; votedFor=0; role=Role.SEGUIDOR; leader=0; votes.clear();
        persist();
        log.accept("TERMO;p"+partition+";termo="+t+";anterior="+old);
    }
    public synchronized void observeTerm(long t) { if(t>term) adoptTerm(t); }
    public synchronized long tick() {
        if(role==Role.LIDER || System.nanoTime()-deadline<0) return -1;
        term=Math.addExact(term,1); votedFor=self; persist();
        role=Role.CANDIDATO; leader=0; votes.clear(); votes.add(self); resetTimer();
        log.accept("CANDIDATO;p"+partition+";termo="+term+";voto=n"+self);
        return term;
    }
    public synchronized Vote onVoteRequest(long t,int candidate,long seq,long lastTerm,long mySeq,long myTerm) {
        Wire.member(candidate);
        if(t>term) adoptTerm(t);
        boolean upToDate=lastTerm>myTerm || (lastTerm==myTerm && seq>=mySeq);
        boolean grant=t==term && (votedFor==0 || votedFor==candidate) && true;
        if(grant) { votedFor=candidate; persist(); resetTimer(); }
        log.accept("VOTO;p"+partition+";termo="+term+";pedidoTermo="+t+";candidato=n"+candidate
                +";"+(grant?"concedido":"recusado"));
        return new Vote(term,grant);
    }
    public synchronized boolean onVote(long t,int voter,int candidate,boolean granted) {
        Wire.member(voter); Wire.member(candidate);
        if(t>term) { adoptTerm(t); return false; }
        if(role!=Role.CANDIDATO || t!=term || candidate!=self || !granted) return false;
        votes.add(voter);
        if(votes.size()<=groupSize/2) return false;
        role=Role.LIDER; leader=self;
        log.accept("LIDER;p"+partition+";termo="+term+";votos="+new TreeSet<>(votes));
        return true;
    }
    public synchronized boolean onBatch(long t,int from) {
        Wire.member(from);
        if(t<term) return false;
        if(t>term) adoptTerm(t);
        if(role==Role.LIDER || (leader!=0 && leader!=from)) {
            log.accept("ERRO;p"+partition+";termo="+term+";dois lideres no mesmo termo");
            return false;
        }
        role=Role.SEGUIDOR;
        if(leader!=from) {
            leader=from;
            log.accept("NOVO_LIDER;p"+partition+";termo="+term+";lider=n"+from);
        }
        resetTimer(); return true;
    }
    public synchronized long termIfLeader(){return role==Role.LIDER?term:-1;}
    public synchronized long term(){return term;}
    public synchronized Role role(){return role;}
    public synchronized int leader(){return leader;}
}
