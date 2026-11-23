package raft04;

import java.nio.file.*;
import java.util.*;

public final class CoreTest {
    static int count;
    static void yes(boolean ok,String name){count++;if(!ok)throw new AssertionError(name);}
    static void expires(RaftState s)throws Exception{
        java.lang.reflect.Field f=RaftState.class.getDeclaredField("deadline");f.setAccessible(true);f.setLong(s,System.nanoTime()-1);
    }
    static RaftState state(Path d,int id)throws Exception{return new RaftState(1,id,3,6000,12000,d.resolve("state"+id),x->{});}
    public static void main(String[] args)throws Exception{
        Path d=Files.createTempDirectory("raft04-core-");
        RaftState a=state(d,1),b=state(d,2),c=state(d,3);
        expires(a);yes(a.tick()==1,"candidatura termo 1");
        yes(Files.readString(d.resolve("state1")).trim().equals("1;1"),"persistir antes de pedir voto");
        yes(!a.onVote(1,1,1,true),"autovoto nao e maioria");
        yes(b.onVoteRequest(1,1,0,0,0,0).granted,"voto concedido");
        yes(Files.readString(d.resolve("state2")).trim().equals("1;1"),"persistir antes de responder voto");
        yes(!state(d,2).onVoteRequest(1,3,0,0,0,0).granted,"reinicio nao permite segundo voto");
        yes(b.onVoteRequest(1,1,0,0,0,0).granted,"repeticao do mesmo candidato");
        yes(a.onVote(1,2,1,true),"maioria de dois elege");
        yes(!a.onVote(1,3,1,true),"lider anunciado apenas uma vez");
        yes(!c.onVoteRequest(2,2,4,1,1,2).granted,"ultimo termo prevalece sobre comprimento");
        yes(c.term()==2,"pedido recusado tambem adota termo superior");
        yes(c.onVoteRequest(2,2,1,2,1,2).granted,"historico igualmente atualizado");
        yes(!c.onVoteRequest(1,1,99,1,1,2).granted,"recusar pedido obsoleto");
        yes(a.onBatch(3,3),"demissao perante termo superior");
        yes(a.termIfLeader()==-1 && a.term()==3,"papel e termo atualizados");
        yes(!a.onBatch(2,2),"recusar lote obsoleto");
        yes(state(d,1).term()==3,"termo de demissao persistido");
        VectorClock v=VectorClock.parse("0,1,0",3);
        SensorRecord r=SensorRecord.now(1,2,"S02",29.3,1,v);
        v.tick(0);yes(r.getVector().get(0)==0,"carimbo imutavel por copia");
        Batch batch=new Batch(3,2,"127.0.0.1",7004,1,1,2,List.of(r));
        yes(Batch.decode(batch.encode(),3).records.get(0).toLine().equals(r.toLine()),"roundtrip lote");
        boolean rejected=false;try{Batch.decode(batch.encode()+"\n",3);}catch(IllegalArgumentException e){rejected=true;}
        yes(rejected,"quantidade de linhas estrita");
        yes(VoteRequest.decode(new VoteRequest(3,1,1,2).encode()).lastTerm==2,"roundtrip pedido");
        yes(!VoteReply.decode(new VoteReply(3,2,1,false).encode()).granted,"roundtrip recusa");
        RecordFile file=new RecordFile(d.resolve("records"),3);file.append(r);
        SensorRecord r2=SensorRecord.now(2,3,"S05",21,2,VectorClock.parse("0,2,0",3));
        file.append(r2);file.replace(List.of(r));
        yes(file.last().getSeq()==1 && new RecordFile(d.resolve("records"),3).snapshot().size()==1,"truncagem atomica persistida");
        HeartbeatMonitor m=new HeartbeatMonitor(1,2000,3,x->{});
        yes(m.onHeartbeat(2,1,1),"primeiro heartbeat");
        yes(!m.onHeartbeat(1,2,8),"monitor ignora termo inferior");
        yes(!m.onHeartbeat(2,1,1),"monitor ignora duplicado");
        yes(m.onHeartbeat(3,2,1),"monitor reconhece novo lider com hb reiniciado");
        System.out.println("CORE TESTS: OK ("+count+" verificacoes)");
    }
}
