package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class RecordFile {
    private final Path path;
    private final List<SensorRecord> records=new ArrayList<>();
    public RecordFile(Path path,int n) throws IOException {
        this.path=path;
        if(!Files.exists(path)) Files.createFile(path);
        if(Files.exists(path)) for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)) {
            if(line.isBlank()) continue;
            SensorRecord r=SensorRecord.fromLine(line,n);
            if(r.getSeq()!=records.size()+1L || (!records.isEmpty() && r.getTerm()<last().getTerm()))
                throw new IOException("Historico fora de ordem: "+path);
            records.add(r);
        }
    }
    public synchronized SensorRecord last(){return records.isEmpty()?null:records.get(records.size()-1);}
    public synchronized List<SensorRecord> snapshot(){return new ArrayList<>(records);}
    public synchronized void append(SensorRecord r) throws IOException {
        if(r.getSeq()!=records.size()+1L) throw new IOException("Append fora de ordem");
        Files.writeString(path,r.toLine()+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        records.add(r);
    }
    public synchronized void replace(List<SensorRecord> next) throws IOException {
        for(int j=0;j<next.size();j++) if(next.get(j).getSeq()!=j+1L) throw new IOException("Historico nao contiguo");
        Path tmp=path.resolveSibling(path.getFileName()+".tmp");
        List<String> lines=new ArrayList<>();
        for(SensorRecord r:next) lines.add(r.toLine());
        Files.write(tmp,lines,StandardCharsets.UTF_8);
        Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        records.clear(); records.addAll(next);
    }
    public synchronized List<SensorRecord> range(long from,long to) {
        if(from<1 || to<from || to>records.size()) throw new IllegalArgumentException("Intervalo indisponivel");
        return new ArrayList<>(records.subList(Math.toIntExact(from-1),Math.toIntExact(to)));
    }
}
