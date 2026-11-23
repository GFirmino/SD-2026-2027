package rep03;

import java.time.LocalDateTime;

public final class SensorRecord {
    private final long seq;
    private final String sensorId;
    private final double temperature;
    private final String timestamp;
    private final long lamport;
    private final VectorClock vector;

    public SensorRecord(long seq, String sensorId, double temperature, String timestamp,
                        long lamport, VectorClock vector) {
        Wire.validateReading(sensorId, temperature);
        if (seq <= 0 || lamport < 0) throw new IllegalArgumentException("seq/L inválido");
        LocalDateTime.parse(timestamp);
        this.seq = seq;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.lamport = lamport;
        this.vector = vector.copy(); // o registo conserva o carimbo do seu evento
    }

    public static SensorRecord now(long seq, String sensor, double temp, long l, VectorClock v) {
        return new SensorRecord(seq, sensor, temp, LocalDateTime.now().toString(), l, v);
    }

    public String toLine() {
        return seq + ";" + sensorId + ";" + temperature + ";" + timestamp + ";" + lamport + ";" + vector;
    }

    public static SensorRecord fromLine(String line) { return fromLine(line, Wire.DEFAULT_N); }

    public static SensorRecord fromLine(String line, int n) {
        String[] f = line.trim().split(";", -1);
        if (f.length != 6) throw new IllegalArgumentException("Registo mal formado: " + line);
        return new SensorRecord(Long.parseLong(f[0]), f[1], Double.parseDouble(f[2]), f[3],
                Long.parseLong(f[4]), VectorClock.parse(f[5], n));
    }

    public long getSeq() { return seq; }
    public String getSensorId() { return sensorId; }
    public double getTemperature() { return temperature; }
    public String getTimestamp() { return timestamp; }
    public long getLamport() { return lamport; }
    public VectorClock getVector() { return vector.copy(); }
}
