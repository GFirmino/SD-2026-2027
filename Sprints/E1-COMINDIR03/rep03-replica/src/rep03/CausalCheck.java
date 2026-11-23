package rep03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Análise posterior; nunca participa na entrega ou na escrita das réplicas. */
public final class CausalCheck {
    private static final class Event {
        final int k;
        final SensorRecord r;
        final VectorClock v;
        Event(int k, SensorRecord r) { this.k = k; this.r = r; this.v = r.getVector(); }
        String id() { return "p" + k + "#" + r.getSeq(); }
    }

    private long violations;
    private void violation(String reason) {
        violations++;
        if (violations <= 20) System.out.println("VIOLAÇÃO: " + reason);
    }

    public boolean check(int n, Path dir) throws IOException {
        violations = 0;
        Partitioner partitioner = new Partitioner(n);
        if (!Files.isDirectory(dir)) throw new IOException("Pasta inexistente: " + dir);
        List<Event> events = new ArrayList<>();
        boolean found = false;
        long alarmCount = 0, plainBytes = 0, stampedBytes = 0, vectorBytes = 0;
        System.out.println("CAUSAL CHECK: N=" + n + " pasta=" + dir.toAbsolutePath());
        for (int k = 0; k < n; k++) {
            Path path = dir.resolve("lider-p" + k + ".txt");
            List<String> lines = Files.exists(path)
                    ? Files.readAllLines(path, StandardCharsets.UTF_8) : Collections.emptyList();
            found |= Files.exists(path);
            long expected = 1;
            int count = 0, lineNumber = 0;
            Event previous = null;
            for (String line : lines) {
                lineNumber++;
                if (line.isBlank()) continue;
                final SensorRecord r;
                try { r = SensorRecord.fromLine(line, n); }
                catch (IllegalArgumentException e) {
                    throw new IOException(path.getFileName() + ":" + lineNumber + ": " + e.getMessage(), e);
                }
                Event e = new Event(k, r);
                if (r.getSeq() != expected) violation(e.id() + ": esperado seq=" + expected);
                expected++;
                if (partitioner.of(r.getSensorId()) != k) violation(e.id() + ": sensor na partição errada");
                if (e.v.get(k) != r.getSeq()) violation(e.id() + ": V[k] != seq");
                if (previous != null) {
                    if (previous.v.compare(e.v) != VectorClock.Order.BEFORE)
                        violation(previous.id() + " -> " + e.id() + ": vetor não avança");
                    if (previous.r.getLamport() >= r.getLamport())
                        violation(previous.id() + " -> " + e.id() + ": L não cresce");
                }
                previous = e;
                events.add(e);
                count++;
                if (r.getSensorId().startsWith("ALARM-")) alarmCount++;
                // Custo da mensagem da aplicação em UTF-8, sem HTTP/PubSub/TCP.
                String full = Wire.reg("127.0.0.1", Wire.PORTO_BASE + k, r);
                String plain = full.substring(0, full.lastIndexOf(';'));
                plain = plain.substring(0, plain.lastIndexOf(';'));
                plainBytes += plain.getBytes(StandardCharsets.UTF_8).length;
                stampedBytes += full.getBytes(StandardCharsets.UTF_8).length;
                vectorBytes += e.v.toString().getBytes(StandardCharsets.UTF_8).length;
            }
            System.out.println("Registos p" + k + ": " + count + (Files.exists(path) ? "" : " (ficheiro ausente)"));
        }
        if (!found) throw new IOException("Nenhum ficheiro lider-p*.txt encontrado");

        long before = 0, after = 0, concurrent = 0, distinctL = 0, equalVectors = 0;
        for (int i = 0; i < events.size(); i++) {
            Event a = events.get(i);
            for (int j = i + 1; j < events.size(); j++) {
                Event b = events.get(j);
                if (a.k == b.k) continue;
                switch (a.v.compare(b.v)) {
                    case BEFORE:
                        before++;
                        if (a.r.getLamport() >= b.r.getLamport())
                            violation(a.id() + " -> " + b.id() + ": condição de Lamport");
                        break;
                    case AFTER:
                        after++;
                        if (b.r.getLamport() >= a.r.getLamport())
                            violation(b.id() + " -> " + a.id() + ": condição de Lamport");
                        break;
                    case CONCURRENT:
                        concurrent++;
                        if (a.r.getLamport() != b.r.getLamport()) distinctL++;
                        break;
                    case EQUAL:
                        equalVectors++;
                        violation(a.id() + " e " + b.id() + ": eventos distintos com vetores iguais");
                        break;
                    default: throw new AssertionError();
                }
            }
        }
        System.out.println("Alarmes registados: " + alarmCount);
        System.out.println("Pares entre partições: " + (before + after + concurrent + equalVectors));
        System.out.println("Pares a -> b (a na partição menor): " + before);
        System.out.println("Pares b -> a (a na partição menor): " + after);
        System.out.println("Pares causais: " + (before + after));
        System.out.println("Pares concorrentes: " + concurrent);
        System.out.println("Concorrentes com L distinto: " + distinctL);
        System.out.printf(Locale.ROOT, "Proporção com L distinto: %s%n", concurrent == 0 ? "N/A (0 pares)"
                : String.format(Locale.ROOT, "%.6f (%d/%d)", (double) distinctL / concurrent, distinctL, concurrent));
        System.out.println("Bytes: REG reconstruído com endereço 127.0.0.1 e porto 7000+k; só payload UTF-8.");
        if (!events.isEmpty()) {
            System.out.printf(Locale.ROOT, "REG médio sem carimbos (bytes): %.3f%n", (double) plainBytes / events.size());
            System.out.printf(Locale.ROOT, "REG médio com carimbos (bytes): %.3f%n", (double) stampedBytes / events.size());
            System.out.printf(Locale.ROOT, "Vetor médio (bytes, sem separador): %.3f%n", (double) vectorBytes / events.size());
        }

        events.sort(Comparator.comparingLong((Event e) -> e.r.getLamport())
                .thenComparingInt(e -> e.k).thenComparingLong(e -> e.r.getSeq()));
        List<String> view = new ArrayList<>();
        for (Event e : events) view.add("p" + e.k + ";" + e.r.toLine());
        Files.write(dir.resolve("vista-lamport.txt"), view, StandardCharsets.UTF_8);
        System.out.println("Violações: " + violations);
        System.out.println("RESULTADO: " + (violations == 0 ? "OK" : "FALHOU"));
        return violations == 0;
    }

    public static void main(String[] args) {
        try {
            if (args.length != 2) throw new IllegalArgumentException("Uso: CausalCheck <N> <pasta>");
            if (!new CausalCheck().check(Integer.parseInt(args[0]), Paths.get(args[1]))) System.exit(2);
        } catch (Exception e) {
            System.err.println("ERRO: " + e.getMessage());
            System.exit(1);
        }
    }
}
