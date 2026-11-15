package br.rustland.killmoney;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controla quanto pagar por kill de um MESMO jogador (par assassino -> vitima).
 *
 * Regra: a 1a kill paga amounts[0], a 2a paga amounts[1], etc. Depois da ultima
 * kill paga, kills no mesmo jogador pagam 0. Quando passa o tempo de reset
 * desde a ultima kill PAGA daquele par, a contagem volta ao inicio.
 * Kills que pagam 0 nao estendem o tempo de espera.
 */
public final class RewardTracker {

    public static final class Entry {
        private final int count;
        private final long last;

        Entry(int count, long last) {
            this.count = count;
            this.last = last;
        }

        public int count() {
            return count;
        }

        public long last() {
            return last;
        }
    }

    private final Map<String, Entry> map = new HashMap<>();
    private List<Long> amounts = new ArrayList<>();
    private long resetMs;

    public RewardTracker(List<Long> amounts, long resetMs) {
        configure(amounts, resetMs);
    }

    public synchronized void configure(List<Long> amounts, long resetMs) {
        this.amounts = new ArrayList<>(amounts);
        this.resetMs = resetMs;
    }

    public static String key(UUID killer, UUID victim) {
        return killer + "_" + victim;
    }

    /** Retorna o valor a pagar (0 = nada) e ja atualiza a contagem do par. */
    public synchronized long reward(UUID killer, UUID victim, long now) {
        String key = key(killer, victim);
        Entry e = map.get(key);
        if (e != null && now - e.last >= resetMs) {
            map.remove(key);
            e = null;
        }
        int count = e == null ? 0 : e.count;
        if (count >= amounts.size()) {
            return 0L;
        }
        long amount = amounts.get(count);
        map.put(key, new Entry(count + 1, now));
        return amount;
    }

    public synchronized void load(String key, int count, long last) {
        if (count > 0) {
            map.put(key, new Entry(count, last));
        }
    }

    /** Copia dos dados atuais, ja sem os pares que expiraram. */
    public synchronized Map<String, Entry> snapshot(long now) {
        Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().last >= resetMs) {
                it.remove();
            }
        }
        return new HashMap<>(map);
    }
}
