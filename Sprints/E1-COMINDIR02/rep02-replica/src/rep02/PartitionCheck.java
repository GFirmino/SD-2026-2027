package rep02;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PartitionCheck {

    private static List<SensorRecord> readRecords(Path p) throws IOException {
        List<SensorRecord> list = new ArrayList<>();
        if (!Files.exists(p)) return list;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                list.add(SensorRecord.fromLine(line));
            }
        }
        return list;
    }

    private static boolean checkSequence(List<SensorRecord> records) {
        long expected = 1;
        for (SensorRecord r : records) {
            if (r.getSeq() != expected) {
                return false;
            }
            expected++;
        }
        return true;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: PartitionCheck <N> <pasta>");
            return;
        }

        int n = Integer.parseInt(args[0]);
        Path dir = Paths.get(args[1]);
        Partitioner partitioner = new Partitioner(n);

        long somaParticoes = 0;
        Set<String> todosRegistosUnicos = new HashSet<>();

        System.out.println("=== RELATÓRIO PARTITION CHECK (N=" + n + ", pasta=" + dir.toAbsolutePath() + ") ===");

        try {
            for (int k = 0; k < n; k++) {
                final int part = k;
                Path leaderPath = dir.resolve("lider-p" + k + ".txt");
                List<SensorRecord> leaderRecords = readRecords(leaderPath);

                boolean sensoresCorretos = true;
                Set<String> sensoresDistintos = new HashSet<>();

                for (SensorRecord r : leaderRecords) {
                    sensoresDistintos.add(r.getSensorId());
                    todosRegistosUnicos.add("p" + k + ":" + r.toLine());
                    if (partitioner.of(r.getSensorId()) != k) {
                        sensoresCorretos = false;
                    }
                }

                boolean seqLiderOk = checkSequence(leaderRecords);
                somaParticoes += leaderRecords.size();

                System.out.println("\n--- Partição p" + k + " (lider-p" + k + ".txt) ---");
                System.out.println("Registos no líder         : " + leaderRecords.size());
                System.out.println("Sensores distintos        : " + sensoresDistintos.size() + " " + sensoresDistintos);
                System.out.println("Sensores com of(s) == " + k + "   : " + (sensoresCorretos ? "OK" : "FALHOU"));
                System.out.println("Sequência 1..N sem saltos : " + (seqLiderOk ? "OK" : "FALHOU"));

                Pattern pat = Pattern.compile("replica-([^-]+)-p" + k + "\\.txt");
                List<Path> replicaFiles;
                try (Stream<Path> stream = Files.list(dir)) {
                    replicaFiles = stream
                            .filter(p -> pat.matcher(p.getFileName().toString()).matches())
                            .sorted()
                            .collect(Collectors.toList());
                }

                System.out.println("Réplicas que guardam p" + k + "   : " + replicaFiles.size() + (replicaFiles.size() == 2 ? " (OK, R=2)" : " (ATENÇÃO: esperado R=2)"));

                for (Path rp : replicaFiles) {
                    List<SensorRecord> repRecords = readRecords(rp);
                    boolean seqRepOk = checkSequence(repRecords);

                    boolean isPrefix = repRecords.size() <= leaderRecords.size();
                    if (isPrefix) {
                        for (int i = 0; i < repRecords.size(); i++) {
                            if (!repRecords.get(i).toLine().equals(leaderRecords.get(i).toLine())) {
                                isPrefix = false;
                                break;
                            }
                        }
                    }

                    int emFalta = leaderRecords.size() - repRecords.size();
                    String estado;
                    if (isPrefix && emFalta == 0) {
                        estado = "IGUAL ao líder";
                    } else if (isPrefix) {
                        estado = "PREFIXO do líder";
                    } else {
                        estado = "DIVERGENTE";
                    }

                    System.out.println("  -> " + rp.getFileName() + ": " + estado
                            + " | seq OK=" + seqRepOk
                            + " | registos=" + repRecords.size()
                            + " | em falta=" + emFalta);
                }
            }

            System.out.println("\n=== RESUMO GLOBAL ===");
            System.out.println("Soma dos registos das partições : " + somaParticoes);
            System.out.println("Total de registos do sistema    : " + todosRegistosUnicos.size());
            System.out.println("Soma igual ao total             : " + (somaParticoes == todosRegistosUnicos.size() ? "CONFIRMADO (OK)" : "DIVERGENTE"));

        } catch (IOException e) {
            System.out.println("Erro ao verificar pasta: " + e.getMessage());
        }
    }
}