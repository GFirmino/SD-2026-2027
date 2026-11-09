package raft04;
public final class VoteReply {
    public final long term;
    public final int voter,candidate;
    public final boolean granted;
    public VoteReply(long t,int v,int c,boolean g){
        Wire.member(v);Wire.member(c);
        if(t<0) throw new IllegalArgumentException("Termo negativo");
        term=t;voter=v;candidate=c;granted=g;
    }
    public String encode(){return "VOTO;"+term+";"+voter+";"+candidate+";"+(granted?"S":"N");}
    public static VoteReply decode(String s){
        String[] f=s.split(";",-1);
        if(f.length!=5 || !f[0].equals("VOTO") || !(f[4].equals("S")||f[4].equals("N")))
            throw new IllegalArgumentException("Voto invalido");
        return new VoteReply(Long.parseLong(f[1]),Integer.parseInt(f[2]),Integer.parseInt(f[3]),f[4].equals("S"));
    }
}
