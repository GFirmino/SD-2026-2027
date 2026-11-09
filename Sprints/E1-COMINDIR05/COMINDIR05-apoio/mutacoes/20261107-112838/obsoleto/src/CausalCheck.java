package raft04;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Conserva a analise da COMINDIR03, escolhendo o historico do ultimo lider de cada grupo. */
public final class CausalCheck {
    private static final class Event {
        final int k;final SensorRecord r;
        Event(int k,SensorRecord r){this.k=k;this.r=r;}
    }
    public static void main(String[] args) {
        try {
            if(args.length!=2)throw new IllegalArgumentException("CausalCheck <N> <pasta>");
            int n=Integer.parseInt(args[0]);Path dir=Path.of(args[1]);List<Event> all=new ArrayList<>();
            long errors=0,causal=0,concurrent=0,distinct=0;
            for(int k=0;k<n;k++) {
                for(int i=1;i<=Wire.GRUPO;i++) {
                    SensorRecord previous=null;long expected=1;
                    for(SensorRecord r:RaftCheck.records(dir.resolve("no-p"+k+"-n"+i+".txt"),n)) {
                        if(r.getSeq()!=expected++ || r.getVector().get(k)!=r.getSeq() || new Partitioner(n).of(r.getSensorId())!=k)errors++;
                        if(previous!=null && (previous.getLamport()>=r.getLamport()
                                || previous.getVector().compare(r.getVector())!=VectorClock.Order.BEFORE))errors++;
                        previous=r;
                    }
                }
                for(SensorRecord r:RaftCheck.records(RaftCheck.leaderPath(dir,k),n))all.add(new Event(k,r));
            }
            for(int a=0;a<all.size();a++)for(int b=a+1;b<all.size();b++) {
                Event x=all.get(a),y=all.get(b);if(x.k==y.k)continue;
                VectorClock.Order order=x.r.getVector().compare(y.r.getVector());
                if(order==VectorClock.Order.BEFORE){causal++;if(x.r.getLamport()>=y.r.getLamport())errors++;}
                else if(order==VectorClock.Order.AFTER){causal++;if(y.r.getLamport()>=x.r.getLamport())errors++;}
                else if(order==VectorClock.Order.CONCURRENT){concurrent++;if(x.r.getLamport()!=y.r.getLamport())distinct++;}
                else errors++;
            }
            all.sort(Comparator.comparingLong((Event e)->e.r.getLamport()).thenComparingInt(e->e.k));
            List<String> view=new ArrayList<>();for(Event e:all)view.add("p"+e.k+";"+e.r.toLine());
            Files.write(dir.resolve("vista-lamport.txt"),view,StandardCharsets.UTF_8);
            String report="CAUSAL CHECK\nRegistos canonicos: "+all.size()+"\nPares causais: "+causal
                +"\nPares concorrentes: "+concurrent+"\nConcorrentes com L distinto: "+distinct
                +"\nViolacoes: "+errors+"\nRESULTADO: "+(errors==0?"OK":"FALHOU")+"\n";
            Files.writeString(dir.resolve("causal-check.txt"),report,StandardCharsets.UTF_8);System.out.print(report);
            if(errors>0)System.exit(2);
        }catch(Exception e){e.printStackTrace();System.exit(1);}
    }
}
