package rep04;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public final class HeartbeatCheck {
    private static class HB {
        long inc;
        long hb;
        long time;
        long last;
        long count;
        long bytes;

        // Este método serve para ler uma linha do ficheiro de heartbeats publicados.
        HB(String line) {
            String[] f = line.split(";", -1);

            if (f.length != 6) throw new IllegalArgumentException("Linha hb invalida: " + line);
            inc = Long.parseLong(f[0]);
            hb = Long.parseLong(f[1]);
            time = Long.parseLong(f[2]);
            last = Long.parseLong(f[3]);
            count = Long.parseLong(f[4]);
            bytes = Long.parseLong(f[5]);
        }
    }

    private static class Event {
        long time;
        String type;
        int k;
        Map<String, String> fields = new HashMap<>();

        // Este método serve para ler o instante, a partição e os detalhes de um evento do monitor.
        Event(String line) {
            String[] f = line.split(";", -1);

            time = Long.parseLong(f[0]);
            type = f[1];
            k = Integer.parseInt(f[2].substring(1));

            for (int i = 3; i < f.length; i++) {
                String[] pair = f[i].split("=", 2);

                if (pair.length == 2) fields.put(pair[0], pair[1]);
            }
        }

        // Este método serve para obter um detalhe numérico do evento ou zero quando está ausente.
        long value(String key) {
            return Long.parseLong(fields.getOrDefault(key, "0"));
        }
    }

    private static class Stats {
        long count;
        long sum;
        long max = Long.MIN_VALUE;

        // Este método serve para acumular uma amostra para calcular quantidade, média e máximo.
        void add(long value) {
            count++;
            sum += value;
            max = Math.max(max, value);
        }

        // Este método serve para apresentar as estatísticas das amostras acumuladas.
        String result() {
            return count == 0
                    ? "sem amostras"
                    : String.format(
                            Locale.ROOT,
                            "n=%d media=%.3f max=%d",
                            count,
                            (double) sum / count,
                            max);
        }
    }

    // Este método serve para ler as linhas úteis de um ficheiro, tolerando a sua ausência e o BOM.
    private static List<String> lines(Path p) throws Exception {
        return Files.exists(p)
                ? Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                        .map((s) -> s.replace("\ufeff", ""))
                        .filter((s) -> !s.isBlank())
                        .collect(Collectors.toList())
                : Collections.emptyList();
    }

    // Este método serve para analisar lotes, suspeitas, tempos de deteção, latências e custos em
    // bytes.
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("Uso: HeartbeatCheck <N> <pasta> [falhas.txt]");
        int n = Integer.parseInt(args[0]);
        Path folder = Path.of(args[1]);
        List<List<HB>> all = new ArrayList<>();
        List<Map<Long, SensorRecord>> records = new ArrayList<>();

        StringBuilder out =
                new StringBuilder(
                        "HEARTBEAT CHECK\n"
                                + "Unidades: ms; bytes UTF-8 do protocolo da aplicacao (sem"
                                + " HTTP/IPFS/TCP).\n");
        long totalBatches = 0;
        long totalRecords = 0;
        long totalBytes = 0;
        long totalRegBytes = 0;

        for (int k = 0; k < n; k++) {
            List<HB> hbs = new ArrayList<>();

            for (String line : lines(folder.resolve("hb-p" + k + ".txt"))) hbs.add(new HB(line));
            all.add(hbs);
            Map<Long, SensorRecord> rec = new TreeMap<>();

            for (String line : lines(folder.resolve("lider-p" + k + ".txt"))) {
                SensorRecord r = SensorRecord.fromLine(line, n);

                rec.put(r.getSeq(), r);
            }

            records.add(rec);
            Map<Long, List<HB>> groups = new LinkedHashMap<>();

            for (HB h : hbs) groups.computeIfAbsent(h.inc, (x) -> new ArrayList<>()).add(h);

            if (groups.isEmpty()) out.append("p").append(k).append(": sem heartbeats publicados\n");

            for (Map.Entry<Long, List<HB>> entry : groups.entrySet()) {
                List<HB> group = entry.getValue();
                Stats sizes = new Stats();
                Stats intervals = new Stats();
                long empty = 0;

                for (int i = 0; i < group.size(); i++) {
                    HB h = group.get(i);

                    sizes.add(h.count);

                    if (h.count == 0) empty++;

                    if (i > 0) intervals.add(h.time - group.get(i - 1).time);
                    totalBatches++;
                    totalRecords += h.count;
                    totalBytes += h.bytes;
                }

                out.append(
                        String.format(
                                Locale.ROOT,
                                "p%d inc=%d lotes=%d vazios=%d (%.2f%%) registos/lote %s"
                                        + " intervalo_ms %s%n",
                                k,
                                entry.getKey(),
                                group.size(),
                                empty,
                                100.0 * empty / group.size(),
                                sizes.result(),
                                intervals.result()));
            }
        }

        Map<Integer, List<Long>> failures = new HashMap<>();

        if (args.length == 3) {
            Path p = Path.of(args[2]);

            if (!Files.exists(p))
                throw new IllegalArgumentException("Ficheiro de falhas inexistente: " + p);

            for (String line : lines(p)) {
                String[] f = line.split(";");

                failures.computeIfAbsent(
                                Integer.parseInt(f[0].substring(1)), (x) -> new ArrayList<>())
                        .add(Long.parseLong(f[1]));
            }
        }

        Stats[] latency = new Stats[n];

        for (int k = 0; k < n; k++) latency[k] = new Stats();
        long lost = 0;
        long recovery = 0;
        long real = 0;
        long falseSuspicions = 0;
        Map<String, Long> detections = new TreeMap<>();
        Set<String> regMeasured = new HashSet<>();

        try (Stream<Path> stream = Files.list(folder); ) {
            for (Path p :
                    stream.filter((x) -> x.getFileName().toString().matches("monitor-.*\\.txt"))
                            .sorted()
                            .collect(Collectors.toList())) {
                List<Event> events = new ArrayList<>();

                for (String line : lines(p)) events.add(new Event(line));
                long[] currentInc = new long[n];

                Arrays.fill(currentInc, -1);
                Set<String> seen = new HashSet<>();
                String participant = p.getFileName().toString();

                for (int i = 0; i < events.size(); i++) {
                    Event e = events.get(i);

                    if (e.k < 0 || e.k >= n)
                        throw new IllegalArgumentException("Particao fora de N");

                    if (e.type.equals("HB_PERDIDO")) lost++;

                    if (e.type.equals("RECUPERACAO")) recovery++;

                    if (e.type.equals("RECEBIDO")) {
                        currentInc[e.k] = Math.max(currentInc[e.k], e.value("inc"));
                        long from = e.value("de");
                        long to = e.value("ate");

                        if (from > 0)
                            for (SensorRecord r : records.get(e.k).values()) {
                                if (r.getSeq() < from || r.getSeq() > to) continue;

                                if (seen.add(e.k + ":" + r.getSeq())) {
                                    long time =
                                            LocalDateTime.parse(r.getTimestamp())
                                                    .atZone(ZoneId.systemDefault())
                                                    .toInstant()
                                                    .toEpochMilli();
                                    latency[e.k].add(e.time - time);
                                }

                                if (regMeasured.add(
                                        e.k
                                                + ":"
                                                + e.value("inc")
                                                + ":"
                                                + e.value("hb")
                                                + ":"
                                                + r.getSeq())) {
                                    totalRegBytes +=
                                            ("REG;127.0.0.1;"
                                                            + (Wire.PORTO_BASE + e.k)
                                                            + ";"
                                                            + r.toLine())
                                                    .getBytes(StandardCharsets.UTF_8)
                                                    .length;
                                }
                            }
                    }

                    if (!e.type.equals("SUSPEITA")) continue;
                    long inc = currentInc[e.k];
                    boolean resumed = false;

                    for (int j = i + 1; j < events.size(); j++) {
                        Event later = events.get(j);

                        if (later.k != e.k) continue;

                        if (later.type.equals("RETOMA")) {
                            resumed = later.value("inc") == inc;
                            break;
                        }

                        if (later.type.equals("REINICIO")) break;
                    }

                    boolean laterPublication = false;
                    long lastPublished = -1;

                    for (HB h : all.get(e.k))
                        if (h.inc == inc) {
                            if (h.time <= e.time) lastPublished = Math.max(lastPublished, h.time);
                            else laterPublication = true;
                        }

                    String classification;

                    if (resumed) {
                        classification = "FALSA_SUSPEITA";
                        falseSuspicions++;
                    } else if (inc < 0 || laterPublication)
                        classification =
                                "INCONCLUSIVA (sem RETOMA correspondente/sem inc conhecida)";
                    else {
                        classification = "FALHA_REAL segundo criterio da ficha (janela observada)";
                        real++;
                    }

                    out.append(
                            participant
                                    + " p"
                                    + e.k
                                    + " suspeita="
                                    + e.time
                                    + " inc="
                                    + inc
                                    + " "
                                    + classification
                                    + " silencio_desde_publicacao_ms="
                                    + (lastPublished < 0 ? "desconhecido" : e.time - lastPublished)
                                    + "\n");

                    if (classification.startsWith("FALHA_REAL")) {
                        long failure = -1;

                        for (long f : failures.getOrDefault(e.k, Collections.emptyList()))
                            if (f <= e.time && f >= inc) failure = Math.max(failure, f);

                        if (failure >= 0)
                            detections.putIfAbsent(
                                    participant + " p" + e.k + " falha=" + failure,
                                    e.time - failure);
                    }
                }
            }
        }

        for (Map.Entry<String, Long> d : detections.entrySet())
            out.append(d.getKey() + " detecao_ms=" + d.getValue() + "\n");

        for (int k = 0; k < n; k++)
            out.append(
                    "p"
                            + k
                            + " latencia_RECEBIDO_ms (primeira rececao por participante/registo): "
                            + latency[k].result()
                            + "\n");
        out.append(
                "HB_PERDIDO="
                        + lost
                        + " RECUPERACAO="
                        + recovery
                        + " falhas_reais="
                        + real
                        + " falsas_suspeitas="
                        + falseSuspicions
                        + "\n");
        out.append("Mensagens: LOTE=" + totalBatches + " REG_equivalentes=" + totalRecords + "\n");
        out.append(
                "Bytes/registro LOTE (inclui lotes vazios): "
                        + (totalRecords == 0 ? "N/A" : (double) totalBytes / totalRecords)
                        + "\n");
        out.append(
                "Bytes/registro REG equivalente (registos observados, endereco local): "
                        + (regMeasured.isEmpty()
                                ? "N/A"
                                : (double) totalRegBytes / regMeasured.size())
                        + "\n");
        out.append(
                "Sem falhas.txt nao se mede tempo desde a falha. Fim de captura nao prova morte; a"
                        + " classificacao depende da janela observada.\n");
        Files.writeString(
                folder.resolve("heartbeat-check.txt"), out.toString(), StandardCharsets.UTF_8);
        System.out.print(out);
    }
}
