package raft04;

import java.nio.file.Files;
import java.nio.file.Paths;

public final class Wire {
    public static final int DEFAULT_N = 3, GRUPO = 3, VERIFICACAO_MS = 200;
    public static final int PORTO_BASE = Integer.getInteger("raft04.portoBase", 7000);
    public static final int T_TCP_MS = 5000;
    public static final double LIMIAR_ALARME = 28.0;
    public static final long PERIODO_HB_MS = Long.getLong("raft04.periodo", 2000L);
    public static final int FATOR_TIMEOUT = Integer.getInteger("raft04.fator", 3);
    public static final long ELEICAO_MIN_MS = Long.getLong("raft04.eleicaoMin", 3 * PERIODO_HB_MS);
    public static final long ELEICAO_MAX_MS = Long.getLong("raft04.eleicaoMax", 6 * PERIODO_HB_MS);
    public static final String API = System.getProperty("raft04.api", "http://127.0.0.1:5001/api/v0");
    static {
        if (PERIODO_HB_MS < 1 || FATOR_TIMEOUT < 1 || ELEICAO_MIN_MS < 1
                || ELEICAO_MAX_MS < ELEICAO_MIN_MS || ELEICAO_MAX_MS > Long.MAX_VALUE / 1000000L - 1)
            throw new IllegalArgumentException("Temporizacoes invalidas");
    }
    private Wire() { }
    public static String topicIn(int k) { return "raft04-in-p" + k; }
    public static String topicData(int k) { return "raft04-p" + k; }
    public static String topicElection(int k) { return "raft04-eleicao-p" + k; }
    public static int nodePort(int k, int i) {
        int port = PORTO_BASE + k * GRUPO + i - 1;
        if (k < 0 || i < 1 || i > GRUPO || port < 1 || port > 65535)
            throw new IllegalArgumentException("No/porto invalido");
        return port;
    }
    public static boolean isolated(int k, int i) { return Files.exists(Paths.get("isolar-p"+k+"-n"+i)); }
    public static void member(int i) {
        if (i < 1 || i > GRUPO) throw new IllegalArgumentException("Membro invalido: " + i);
    }
    public static void validateReading(String sensor, double temp) {
        if (sensor == null || sensor.isEmpty() || sensor.indexOf(';') >= 0
                || sensor.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || !Double.isFinite(temp)) throw new IllegalArgumentException("Leitura invalida");
    }
    public static String in(String sensor, double temp, long l, VectorClock v) {
        validateReading(sensor, temp);
        if (l < 0) throw new IllegalArgumentException("Lamport negativo");
        return "IN;"+sensor+";"+temp+";"+l+";"+v;
    }
    public static String[] inParts(String msg) {
        String[] f = msg.split(";", -1);
        if (f.length != 5 || !f[0].equals("IN")) throw new IllegalArgumentException("IN invalido");
        return new String[] {f[1], f[2], f[3], f[4]};
    }
}
