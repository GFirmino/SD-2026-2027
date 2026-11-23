package raft04;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.lang.reflect.*;

/** Intercalacoes deterministicas; nao dependem de acertar uma janela de milissegundos no Windows. */
public final class MutationTest {
    static RaftState[][] states=new RaftState[3][4];
    static void expire(RaftState s)throws Exception{Field f=RaftState.class.getDeclaredField("deadline");f.setAccessible(true);f.setLong(s,System.nanoTime()-1);s.tick();}
    static RaftState create(int k,int i)throws Exception{
        EventLog log=new EventLog("eventos-p"+k+"-n"+i+".txt");
        RaftState s=new RaftState(k,i,3,6000,12000,Path.of("estado-p"+k+"-n"+i+".txt"),log::event);
        log.event("TERMO;p"+k+";termo="+s.term()+";arranque=sim");return s;
    }
    static void elect(int k,int leader)throws Exception{
        RaftState a=states[k][leader];expire(a);
        int voter=leader%3+1;
        RaftState.Vote v=states[k][voter].onVoteRequest(a.term(),leader,0,0,0,0);
        if(!a.onVote(v.term,voter,leader,v.granted))throw new AssertionError("Sem lider de controlo");
    }
    static void invoke(PartitionNode node,String method,String message)throws Exception{
        Method m=PartitionNode.class.getDeclaredMethod(method,String.class);m.setAccessible(true);
        m.invoke(node,message);
    }
    static SensorRecord record(int seq,int term){return SensorRecord.now(seq,term,"S02",20+seq/100.0,seq,VectorClock.parse("0,"+seq+",0",3));}
    public static void main(String[] args)throws Exception{
        String mode=args[0];
        for(int k=0;k<3;k++)for(int i=1;i<=3;i++){
            Files.writeString(Path.of("estado-p"+k+"-n"+i+".txt"),(mode.equals("atualizacao") && k==1?"1;0\n":"0;0\n"));
            new RecordFile(Path.of("no-p"+k+"-n"+i+".txt"),3);
            states[k][i]=create(k,i);
        }
        elect(0,1);elect(2,1);
        RaftState a=states[1][1],b=states[1][2],c=states[1][3];
        if(mode.equals("maioria")){
            expire(a);expire(c);
            if(!a.onVote(1,1,1,true)||!c.onVote(1,3,3,true))throw new AssertionError("Mutacao nao ativada");
        }else if(mode.equals("persistencia")){
            expire(a);expire(c);
            if(!b.onVoteRequest(1,1,0,0,0,0).granted)throw new AssertionError();
            a.onVote(1,2,1,true);
            b=create(1,2); // falha/reinicio depois do voto, antes do fim da eleicao
            if(!b.onVoteRequest(1,3,0,0,0,0).granted)throw new AssertionError("Voto nao esquecido");
            c.onVote(1,2,3,true);
        }else if(mode.equals("atualizacao")){
            expire(a);
            for(int i=2;i<=3;i++){
                RecordFile file=new RecordFile(Path.of("no-p1-n"+i+".txt"),3);
                file.append(record(1,1));file.append(record(2,1));
            }
            if(!b.onVoteRequest(2,1,0,0,2,1).granted)throw new AssertionError("Restricao nao removida");
            a.onVote(2,2,1,true);
            for(int i=2;i<=3;i++)invoke(new PartitionNode(1,i,3),"dataMessage",new Batch(2,1,"127.0.0.1",7003,1,0,0,List.of()).encode());
        }else if(mode.equals("obsoleto")){
            elect(1,1);
            for(int i=1;i<=3;i++)new RecordFile(Path.of("no-p1-n"+i+".txt"),3).append(record(1,1));
            Files.writeString(Path.of("estado-p1-n2.txt"),"3;0\n");
            PartitionNode node=new PartitionNode(1,2,3);
            invoke(node,"dataMessage",new Batch(2,3,"127.0.0.1",7005,1,0,0,List.of()).encode());
            if(!Files.readString(Path.of("no-p1-n2.txt")).isEmpty())throw new AssertionError("Lote obsoleto nao aplicado");
        }else if(mode.equals("relogios")){
            elect(1,1);
            for(int i=1;i<=3;i++)new RecordFile(Path.of("no-p1-n"+i+".txt"),3).append(record(1,1));
            PartitionNode node=new PartitionNode(1,1,3);
            Field field=PartitionNode.class.getDeclaredField("raft");field.setAccessible(true);RaftState r=(RaftState)field.get(node);
            expire(r);r.onVote(r.term(),2,1,true);
            Method become=PartitionNode.class.getDeclaredMethod("becomeLeader");become.setAccessible(true);become.invoke(node);
            boolean blocked=false;
            try{invoke(node,"input","IN;S02;21.0;0;0,0,0");}catch(InvocationTargetException e){blocked=e.getCause()instanceof IllegalArgumentException;}
            Field timer=PartitionNode.class.getDeclaredField("timer");timer.setAccessible(true);((java.util.concurrent.ScheduledExecutorService)timer.get(node)).shutdownNow();
            if(!blocked)throw new AssertionError("Invariante nao bloqueou relogios a zero");
            System.out.println("INVARIANTE BLOQUEOU A ESCRITA");
        }
        System.out.println("MUTACAO EXECUTADA: "+mode);
    }
}
