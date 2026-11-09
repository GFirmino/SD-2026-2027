package raft04;

import java.util.Arrays;

public final class VectorClock {
    public enum Order {
        BEFORE,
        AFTER,
        EQUAL,
        CONCURRENT;
    }

    private final long[] v;

    // Este método serve para criar um vetor de dimensão n com todas as componentes a zero.
    public VectorClock(int n) {
        if (n < 1) throw new IllegalArgumentException("Dimensão inválida: " + n);
        v = new long[n];
    }

    // Este método serve para construir internamente um vetor com os valores fornecidos.
    private VectorClock(long[] v) {
        this.v = v;
    }

    // Este método serve para confirmar que os dois vetores têm a mesma dimensão.
    private void sameSize(VectorClock other) {
        if (v.length != other.v.length) throw new IllegalArgumentException("Dimensões diferentes");
    }

    // Este método serve para incorporar o máximo de cada componente do vetor recebido.
    public void merge(VectorClock other) {
        sameSize(other);

        for (int i = 0; i < v.length; i++) v[i] = Math.max(v[i], other.v[i]);
    }

    // Este método serve para incrementar a componente do líder que cria o evento.
    public void tick(int k) {
        v[k] = Math.addExact(v[k], 1);
    }

    // Este método serve para consultar uma componente do vetor.
    public long get(int k) {
        return v[k];
    }

    // Este método serve para devolver a dimensão do vetor.
    public int size() {
        return v.length;
    }

    // Este método serve para criar uma cópia independente do vetor.
    public VectorClock copy() {
        return new VectorClock(v.clone());
    }

    // Este método serve para determinar se os vetores são anteriores, posteriores, iguais ou
    // concorrentes.
    public Order compare(VectorClock other) {
        sameSize(other);
        boolean menor = false;
        boolean maior = false;

        for (int i = 0; i < v.length; i++) {
            if (v[i] < other.v[i]) menor = true;

            if (v[i] > other.v[i]) maior = true;
        }

        if (menor && maior) return Order.CONCURRENT;

        if (menor) return Order.BEFORE;

        if (maior) return Order.AFTER;

        return Order.EQUAL;
    }

    // Este método serve para converter o vetor em números separados por vírgulas.
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }

        return sb.toString();
    }

    // Este método serve para ler e validar as componentes de um vetor em texto.
    public static VectorClock parse(String s, int n) {
        if (n < 1) throw new IllegalArgumentException("Dimensão inválida: " + n);
        String[] f = s.trim().split(",", -1);

        if (f.length != n)
            throw new IllegalArgumentException(
                    "Vetor com dimensão " + f.length + ", esperado " + n + ": " + s);
        long[] values = new long[n];

        for (int i = 0; i < n; i++) {
            values[i] = Long.parseLong(f[i].trim());

            if (values[i] < 0) throw new IllegalArgumentException("Componente negativa");
        }

        return new VectorClock(values);
    }

    // Este método serve para verificar se outro objeto representa o mesmo vetor.
    @Override
    public boolean equals(Object o) {
        return o instanceof VectorClock && Arrays.equals(v, ((VectorClock) o).v);
    }

    // Este método serve para calcular um código de dispersão coerente com os valores do vetor.
    @Override
    public int hashCode() {
        return Arrays.hashCode(v);
    }
}
