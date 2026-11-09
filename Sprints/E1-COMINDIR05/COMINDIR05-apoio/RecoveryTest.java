package raft04;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

public final class RecoveryTest {
    static SensorRecord r(int seq,int term){return new SensorRecord(seq,term,"S02",20+seq/100.0,"2026-11-07T12:00:00",seq,VectorClock.parse("0,"+seq+",0",3));}
    static void invoke(PartitionNode node,String method,String text)throws Exception{
        Method m=PartitionNode.class.getDeclaredMethod(method,String.class);m.setAccessible(true);
        try{m.invoke(node,text);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}
    }
    static ServerSocket server(List<SensorRecord> rows,CountDownLatch entered,CountDownLatch release)throws IOException{
        ServerSocket ss=new ServerSocket(0);
        Thread worker=new Thread(()->{
            try{
                while(!ss.isClosed())try(Socket s=ss.accept()){
                    BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
                    String[] q=in.readLine().split(" ");int from=Integer.parseInt(q[1]),to=Integer.parseInt(q[2]);
                    if(entered!=null){entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("latch");}
                    PrintWriter out=new PrintWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8),true);
                    for(SensorRecord r:rows)if(r.getSeq()>=from && r.getSeq()<=to)out.println(r.toLine());
                    out.println("FIM");
                }
            }catch(SocketException e){if(!ss.isClosed())throw new RuntimeException(e);}
            catch(Exception e){throw new RuntimeException(e);}
        });worker.setDaemon(true);worker.start();return ss;
    }
    public static void main(String[] args)throws Exception{
        Path file=Path.of("no-p1-n2.txt");List<String> initial=new ArrayList<>();
        for(int seq=1;seq<=6;seq++)initial.add(r(seq,(seq+1)/2).toLine());Files.write(file,initial,StandardCharsets.UTF_8);
        PartitionNode node=new PartitionNode(1,2,3);
        List<SensorRecord> remote=List.of(r(1,1),r(2,1),r(3,4),r(4,4));
        try(ServerSocket ss=server(remote,null,null)){
            Batch batch=new Batch(5,1,"127.0.0.1",ss.getLocalPort(),1,4,4,List.of());
            invoke(node,"dataMessage",batch.encode());
        }
        List<String> expected=new ArrayList<>();for(SensorRecord r:remote)expected.add(r.toLine());
        if(!Files.readAllLines(file).equals(expected))throw new AssertionError("Recuo por varios blocos falhou");
        if(!Files.readString(Path.of("eventos-p1-n2.txt")).contains("de=3;descartados=4"))throw new AssertionError("Truncagem nao registada");
        invoke(node,"dataMessage",new Batch(4,3,"127.0.0.1",7005,2,0,0,List.of()).encode());
        if(!Files.readAllLines(file).equals(expected))throw new AssertionError("Aplicou lote obsoleto");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);Throwable[] error={null};
        List<SensorRecord> newer=List.of(r(5,5));
        try(ServerSocket ss=server(newer,entered,release)){
            Thread receiving=new Thread(()->{try{invoke(node,"dataMessage",new Batch(5,1,"127.0.0.1",ss.getLocalPort(),2,5,5,List.of()).encode());}catch(Throwable e){error[0]=e;}});
            receiving.start();if(!entered.await(5,TimeUnit.SECONDS))throw new AssertionError("Nao iniciou TCP");
            invoke(node,"electionMessage",new VoteReply(6,3,1,false).encode());
            release.countDown();receiving.join(6000);
            if(receiving.isAlive()||error[0]!=null)throw new AssertionError("Recuperacao bloqueada",error[0]);
        }
        if(!Files.readAllLines(file).equals(expected))throw new AssertionError("Aplicou snapshot de autoridade obsoleta");
        System.out.println("RECOVERY TESTS: OK (multiplos blocos, lote obsoleto, mudanca de termo durante TCP)");
    }
}
