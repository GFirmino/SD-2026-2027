package rep04;

public final class Wire {
    public static final int DEFAULT_N = 3;
    public static final int LIMITE_RETIDOS = 10;
    public static final int T_TCP_MS = 5000;
    public static final int PORTO_BASE = 7000;
    public static final double LIMIAR_ALARME = 28.0;
    public static final long PERIODO_HB_MS = Long.getLong("rep04.periodo", 5000L);
    public static final int FATOR_TIMEOUT = Integer.getInteger("rep04.fator", 3);
    public static final int VERIFICACAO_MS = 1000;

    static {
        if (PERIODO_HB_MS <= 0 || FATOR_TIMEOUT <= 0)
            throw new IllegalArgumentException("Periodo e fator devem ser positivos");
        Math.multiplyExact(Math.multiplyExact(PERIODO_HB_MS, FATOR_TIMEOUT), 1000000L);
    }

    // Este método serve para impedir a criação de objetos desta classe de constantes e formatos.
    private Wire() {}

    // Este método serve para obter o tópico de entrada de uma partição.
    public static String topicIn(int k) {
        return "rep04-in-p" + k;
    }

    // Este método serve para obter o tópico onde uma partição publica os lotes.
    public static String topicData(int k) {
        return "rep04-p" + k;
    }

    // Este método serve para rejeitar identificadores inválidos e temperaturas não finitas.
    public static void validateReading(String sensor, double temp) {
        if (sensor == null
                || sensor.isEmpty()
                || sensor.indexOf(';') >= 0
                || sensor.chars()
                        .anyMatch((c) -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw new IllegalArgumentException("Identificador de sensor inválido");

        if (!Double.isFinite(temp)) throw new IllegalArgumentException("Temperatura não finita");
    }

    // Este método serve para construir uma mensagem IN com a leitura e os relógios.
    public static String in(String sensor, double temp, long l, VectorClock v) {
        validateReading(sensor, temp);

        if (l < 0) throw new IllegalArgumentException("Lamport negativo");

        return "IN;" + sensor + ";" + temp + ";" + l + ";" + v;
    }

    // Este método serve para validar e separar os campos de uma mensagem IN.
    public static String[] inParts(String msg) {
        String[] f = msg.trim().split(";", -1);

        if (f.length != 5 || !f[0].equals("IN"))
            throw new IllegalArgumentException("IN mal formado");

        return new String[] {f[1], f[2], f[3], f[4]};
    }

    // Este método serve para obter o tipo indicado antes do primeiro ponto e vírgula.
    public static String type(String msg) {
        int i = msg.indexOf(';');

        if (i <= 0) throw new IllegalArgumentException("Mensagem sem tipo");

        return msg.substring(0, i);
    }

    // Este método serve para obter o conteúdo da mensagem depois do tipo.
    public static String body(String msg) {
        type(msg);

        return msg.substring(msg.indexOf(';') + 1);
    }
}
