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
