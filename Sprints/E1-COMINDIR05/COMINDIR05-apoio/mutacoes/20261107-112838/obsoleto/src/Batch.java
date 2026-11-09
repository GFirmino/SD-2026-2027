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
