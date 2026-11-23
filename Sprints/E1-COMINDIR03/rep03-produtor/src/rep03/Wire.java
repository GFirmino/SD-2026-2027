package rep03;

public final class Wire {
    public static final int DEFAULT_N = 3;
    public static final int LIMITE_RETIDOS = 10;
    public static final int T_TCP_MS = 5000;
    public static final int PORTO_BASE = 7000;
    public static final double LIMIAR_ALARME = 28.0;
    private Wire() { }

    public static String topicIn(int k) { return "rep03-in-p" + k; }
    public static String topicData(int k) { return "rep03-p" + k; }

    public static void validateReading(String sensor, double temp) {
        if (sensor == null || sensor.isEmpty() || sensor.indexOf(';') >= 0
                || sensor.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw new IllegalArgumentException("Identificador de sensor inválido");
        if (!Double.isFinite(temp)) throw new IllegalArgumentException("Temperatura não finita");
    }

    public static String in(String sensor, double temp, long l, VectorClock v) {
        validateReading(sensor, temp);
        if (l < 0) throw new IllegalArgumentException("Lamport negativo");
        return "IN;" + sensor + ";" + temp + ";" + l + ";" + v;
    }

    public static String[] inParts(String msg) {
        String[] f = msg.trim().split(";", -1);
        if (f.length != 5 || !f[0].equals("IN")) throw new IllegalArgumentException("IN mal formado");
        return new String[] { f[1], f[2], f[3], f[4] };
    }

    public static String reg(String host, int port, SensorRecord r) {
        return "REG;" + host + ";" + port + ";" + r.toLine();
    }

    public static String type(String msg) {
        int i = msg.indexOf(';');
        if (i <= 0) throw new IllegalArgumentException("Mensagem sem tipo");
        return msg.substring(0, i);
    }

    public static String body(String msg) { type(msg); return msg.substring(msg.indexOf(';') + 1); }

    public static String[] regParts(String msg) {
        String[] f = msg.trim().split(";", 4);
        if (f.length != 4 || !f[0].equals("REG") || f[1].isBlank())
            throw new IllegalArgumentException("REG mal formado");
        int port = Integer.parseInt(f[2]);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Porto inválido");
        return new String[] { f[1], f[2], f[3] };
    }
}
