package tpf;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

public record Registo(long lsn, long instante, String autor, String canal, String texto) {
    public Registo {
        if (lsn < 1 || instante < 0) throw new IllegalArgumentException("LSN/instante inválido.");
        validateField(autor);
        validateField(canal);
        Objects.requireNonNull(texto, "texto");
    }

    private static void validateField(String field) {
        if (field == null || field.isBlank() || field.chars().anyMatch(c -> c == ';' || c == '\r' || c == '\n')) {
            throw new IllegalArgumentException("Autor/canal inválido.");
        }
    }

    public String toLine() {
        String encoded = Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
        return lsn + ";" + instante + ";" + autor + ";" + canal + ";" + encoded;
    }

    public static Registo fromLine(String line) {
        String[] fields = line.split(";", -1);
        if (fields.length != 5) throw new IllegalArgumentException("Linha de registo inválida.");
        byte[] text = Base64.getDecoder().decode(fields[4]);
        return new Registo(Long.parseLong(fields[0]), Long.parseLong(fields[1]), fields[2], fields[3],
                new String(text, StandardCharsets.UTF_8));
    }

    int binarySize() {
        int size = Long.BYTES * 2 + Integer.BYTES * 3;
        for (String value : new String[] {autor, canal, texto}) {
            size = Math.addExact(size, value.getBytes(StandardCharsets.UTF_8).length);
        }
        return size;
    }
}
