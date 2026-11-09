package raft04;

import java.time.LocalDateTime;

public final class SensorRecord {
    private final long seq, term, lamport;
    private final String sensorId, timestamp;
    private final double temperature;
    private final VectorClock vector;
    public SensorRecord(long seq, long term, String sensor, double temp, String time, long l, VectorClock v) {
        Wire.validateReading(sensor, temp);
        if (seq < 1 || term < 1 || l < 0) throw new IllegalArgumentException("seq/termo/L invalido");
        LocalDateTime.parse(time);
        this.seq=seq; this.term=term; sensorId=sensor; temperature=temp; timestamp=time;
        lamport=l; vector=v.copy();
    }
    public static SensorRecord now(long seq, long term, String sensor, double temp, long l, VectorClock v) {
        return new SensorRecord(seq,term,sensor,temp,LocalDateTime.now().toString(),l,v);
    }
    public String toLine() { return seq+";"+term+";"+sensorId+";"+temperature+";"+timestamp+";"+lamport+";"+vector; }
    public static SensorRecord fromLine(String line, int n) {
        String[] f=line.split(";",-1);
        if(f.length!=7) throw new IllegalArgumentException("Registo invalido");
        return new SensorRecord(Long.parseLong(f[0]),Long.parseLong(f[1]),f[2],Double.parseDouble(f[3]),
                f[4],Long.parseLong(f[5]),VectorClock.parse(f[6],n));
    }
    public long getSeq(){return seq;}
    public long getTerm(){return term;}
    public long getLamport(){return lamport;}
    public String getSensorId(){return sensorId;}
    public String getTimestamp(){return timestamp;}
    public double getTemperature(){return temperature;}
    public VectorClock getVector(){return vector.copy();}
}
