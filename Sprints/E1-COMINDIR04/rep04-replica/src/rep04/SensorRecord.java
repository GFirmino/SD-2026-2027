package rep04;

import java.time.LocalDateTime;

public final class SensorRecord {
    private final long seq;
    private final String sensorId;
    private final double temperature;
    private final String timestamp;
    private final long lamport;
    private final VectorClock vector;

    // Este método serve para validar e guardar um registo com uma cópia do vetor.
    public SensorRecord(
            long seq,
            String sensorId,
            double temperature,
            String timestamp,
            long lamport,
            VectorClock vector) {
        Wire.validateReading(sensorId, temperature);

        if (seq <= 0 || lamport < 0) throw new IllegalArgumentException("seq/L inválido");
        LocalDateTime.parse(timestamp);
        this.seq = seq;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.lamport = lamport;
        this.vector = vector.copy();
    }

    // Este método serve para criar um registo com a data e hora locais atuais.
    public static SensorRecord now(long seq, String sensor, double temp, long l, VectorClock v) {
        return new SensorRecord(seq, sensor, temp, LocalDateTime.now().toString(), l, v);
    }

    // Este método serve para converter o registo para o formato usado nos ficheiros e lotes.
    public String toLine() {
        return seq + ";"
            + sensorId + ";"
            + temperature + ";"
            + timestamp + ";"
            + lamport + ";"
            + vector;
    }

    // Este método serve para ler um registo usando a dimensão de vetor por omissão.
    public static SensorRecord fromLine(String line) {
        return fromLine(line, Wire.DEFAULT_N);
    }

    // Este método serve para validar os seis campos do registo e a dimensão n do vetor.
    public static SensorRecord fromLine(String line, int n) {
        String[] f = line.trim().split(";", -1);

        if (f.length != 6) throw new IllegalArgumentException("Registo mal formado: " + line);

        return new SensorRecord(
                Long.parseLong(f[0]),
                f[1],
                Double.parseDouble(f[2]),
                f[3],
                Long.parseLong(f[4]),
                VectorClock.parse(f[5], n));
    }

    // Este método serve para devolver o número de sequência do registo.
    public long getSeq() {
        return seq;
    }

    // Este método serve para devolver o identificador do sensor.
    public String getSensorId() {
        return sensorId;
    }

    // Este método serve para devolver a temperatura do registo.
    public double getTemperature() {
        return temperature;
    }

    // Este método serve para devolver o instante em que o registo foi criado.
    public String getTimestamp() {
        return timestamp;
    }

    // Este método serve para devolver o relógio de Lamport do registo.
    public long getLamport() {
        return lamport;
    }

    // Este método serve para devolver uma cópia do vetor, protegendo o carimbo guardado.
    public VectorClock getVector() {
        return vector.copy();
    }
}
