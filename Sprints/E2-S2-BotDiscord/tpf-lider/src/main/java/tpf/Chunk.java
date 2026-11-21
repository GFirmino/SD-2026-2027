package tpf;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Mutável apenas enquanto aberto. O monitor de LeaderStore protege a sua utilização. */
public final class Chunk {
    private static final int MAGIC = 0x54504631; // TPF1
    private static final int HEADER_BYTES = 12;
    private final List<Registo> records = new ArrayList<>();
    private int size = HEADER_BYTES;
    private boolean sealed;

    public void add(Registo record) {
        if (sealed) throw new IllegalStateException("O chunk já está selado.");
        if (!records.isEmpty() && record.lsn() != lastLsn() + 1) {
            throw new IllegalArgumentException("LSN não contíguo no chunk.");
        }
        size = Math.addExact(size, record.binarySize());
        records.add(record);
    }

    public boolean isEmpty() { return records.isEmpty(); }
    public boolean isSealed() { return sealed; }
    public int size() { return size; }
    public long firstLsn() { return records.get(0).lsn(); }
    public long lastLsn() { return records.get(records.size() - 1).lsn(); }
    public long firstInstant() { return records.get(0).instante(); }
    public List<Registo> records() { return List.copyOf(records); }

    public byte[] seal(int padding) throws IOException {
        if (sealed || records.isEmpty()) throw new IllegalStateException("Chunk vazio ou já selado.");
        if (padding < 1) throw new IllegalArgumentException("Padding inválido.");
        long paddedSize = ((long) size + padding - 1) / padding * padding;
        if (paddedSize > Integer.MAX_VALUE) throw new IOException("Chunk demasiado grande.");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(size);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(records.size());
            out.writeInt(size);
            for (Registo record : records) {
                out.writeLong(record.lsn());
                out.writeLong(record.instante());
                writeText(out, record.autor());
                writeText(out, record.canal());
                writeText(out, record.texto());
            }
        }
        byte[] padded = Arrays.copyOf(bytes.toByteArray(), (int) paddedSize);
        sealed = true;
        return padded;
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) throw new IOException("Comprimento de texto inválido.");
        byte[] bytes = in.readNBytes(length);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    public static List<Registo> read(byte[] padded) throws IOException {
        if (padded.length < HEADER_BYTES) throw new IOException("Cabeçalho incompleto.");
        ByteBuffer header = ByteBuffer.wrap(padded);
        if (header.getInt() != MAGIC) throw new IOException("Formato de chunk desconhecido.");
        int count = header.getInt();
        int length = header.getInt();
        if (count < 1 || length < HEADER_BYTES || length > padded.length
                || count > (length - HEADER_BYTES) / 28) throw new IOException("Cabeçalho inválido.");
        List<Registo> result = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(padded, HEADER_BYTES, length - HEADER_BYTES))) {
            for (int i = 0; i < count; i++) {
                Registo record = new Registo(in.readLong(), in.readLong(), readText(in), readText(in), readText(in));
                if (i > 0 && record.lsn() != result.get(i - 1).lsn() + 1) throw new IOException("LSN não contíguo.");
                result.add(record);
            }
            if (in.available() != 0) throw new IOException("Dados adicionais no corpo do chunk.");
        } catch (IllegalArgumentException exception) {
            throw new IOException("Registo inválido no chunk.", exception);
        }
        return List.copyOf(result);
    }
}
