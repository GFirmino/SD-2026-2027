package rep04;

public final class Partitioner {
    private final int n;

    // Este método serve para validar e guardar o número de partições.
    public Partitioner(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("Número de partições inválido: " + n);
        }

        this.n = n;
    }

    // Este método serve para calcular a partição de um sensor a partir do seu nome.
    public int of(String sensorId) {
        return Math.floorMod(sensorId.hashCode(), n);
    }

    // Este método serve para devolver o número de partições configurado.
    public int count() {
        return n;
    }
}
