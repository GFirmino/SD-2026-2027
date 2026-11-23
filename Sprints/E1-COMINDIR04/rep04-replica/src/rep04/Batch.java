package rep04;

import java.util.*;

public final class Batch {
    public final String address;
    public final int port;
    public final long inc;
    public final long hb;
    public final long lastSeq;
    public final List<SensorRecord> records;
    public final int count;

    // Este método serve para criar um lote com um cabeçalho validado e uma cópia dos registos.
    public Batch(String address, int port, long inc, long hb, long lastSeq, List<SensorRecord> records) {
        this(address, port, inc, hb, lastSeq, records.size(), records);
    }

    // Este método serve para criar um lote com um cabeçalho validado e uma cópia dos registos.
    private Batch(String address, int port, long inc, long hb, long lastSeq, int count, List<SensorRecord> records) {
        if (address.isBlank() || address.matches(".*[;\\s].*") 
            || port < 1 || port > 65535 
            || inc < 0 || hb < 1 || lastSeq < 0 || count < 0
        ){
            throw new IllegalArgumentException("Cabecalho LOTE invalido");
        }
        
        this.address = address;
        this.port = port;
        this.inc = inc;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.count = count;
        this.records = List.copyOf(records);
    }

    // Este método serve para converter o lote para a mensagem de texto enviada pelo PubSub.
    public String encode() {
        StringBuilder s = new StringBuilder("LOTE;" + address + ";" + port + ";" + inc + ";" + hb + ";" + lastSeq + ";" + count);

        for (SensorRecord r : records) s.append('\n').append(r.toLine());

        return s.toString();
    }

    // Este método serve para ler apenas o cabeçalho do lote, sem interpretar os registos.
    public static Batch header(String msg) {
        String[] f = msg.split("\\R", 2)[0].split(";", -1);

        if (f.length != 7 || !f[0].equals("LOTE"))
            throw new IllegalArgumentException("LOTE mal formado");

        return new Batch(
                f[1],
                Integer.parseInt(f[2]),
                Long.parseLong(f[3]),
                Long.parseLong(f[4]),
                Long.parseLong(f[5]),
                Integer.parseInt(f[6]),
                Collections.emptyList());
    }

    // Este método serve para ler o lote completo e validar a quantidade e a sequência dos registos.
    public static Batch decode(String msg, int n) {
        Batch h = header(msg);
        String[] lines = msg.split("\\R", -1);

        if (lines.length != h.count + 1)
            throw new IllegalArgumentException("Numero de registos diferente do cabecalho");
        
            List<SensorRecord> records = new ArrayList<>();
        long previous = 0;

        for (int i = 1; i < lines.length; i++) {
            SensorRecord r = SensorRecord.fromLine(lines[i], n);

            if (r.getSeq() <= previous || r.getSeq() > h.lastSeq)
                throw new IllegalArgumentException("Sequencia de lote invalida");
            records.add(r);
            previous = r.getSeq();
        }

        return new Batch(h.address, h.port, h.inc, h.hb, h.lastSeq, records);
    }
}
