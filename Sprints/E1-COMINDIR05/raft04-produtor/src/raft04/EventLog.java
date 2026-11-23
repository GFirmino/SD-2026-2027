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
