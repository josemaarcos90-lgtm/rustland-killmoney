package br.rustland.killmoney;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * Uma linha de loot do airdrop.yml, no formato:
 *   MATERIAL quantidade [peso] [ENCANTO=nivel,ENCANTO=nivel]
 * Exemplos: "DIAMOND 2-5 10"   "DIAMOND_SWORD 1 4 SHARPNESS=4,UNBREAKING=3"
 */
final class LootEntry {

    final String material;
    final int min;
    final int max;
    final int weight;
    final Map<String, Integer> enchants;

    private LootEntry(String material, int min, int max, int weight, Map<String, Integer> enchants) {
        this.material = material;
        this.min = min;
        this.max = max;
        this.weight = weight;
        this.enchants = enchants;
    }

    static LootEntry parse(String spec) {
        String[] p = spec.trim().split("\\s+");
        if (p.length < 2) {
            throw new IllegalArgumentException("formato: MATERIAL quantidade [peso] [ENCANTO=nivel,...]");
        }
        int[] range = parseRange(p[1]);
        int weight = p.length >= 3 ? Integer.parseInt(p[2]) : 1;
        Map<String, Integer> ench = new LinkedHashMap<>();
        if (p.length >= 4) {
            for (String e : p[3].split(",")) {
                String[] kv = e.split("=");
                ench.put(kv[0].toLowerCase(Locale.ROOT), kv.length > 1 ? Integer.parseInt(kv[1]) : 1);
            }
        }
        return new LootEntry(p[0].toUpperCase(Locale.ROOT), range[0], range[1], Math.max(0, weight), ench);
    }

    /** "4" ou "3-5" (minimo 1). */
    static int[] parseRange(String s) {
        String[] parts = s.trim().split("-");
        int a = Integer.parseInt(parts[0].trim());
        int b = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : a;
        a = Math.max(1, a);
        b = Math.max(a, b);
        return new int[] {a, b};
    }

    static int roll(int[] range, Random r) {
        return range[0] + r.nextInt(range[1] - range[0] + 1);
    }

    int rollAmount(Random r) {
        return min + r.nextInt(max - min + 1);
    }

    /** Sorteio por peso. Retorna null se a soma dos pesos for 0. */
    static <T> T pick(List<T> items, ToIntFunction<T> weight, Random r) {
        long total = 0;
        for (T t : items) {
            total += Math.max(0, weight.applyAsInt(t));
        }
        if (total <= 0) {
            return null;
        }
        long roll = (long) (r.nextDouble() * total);
        for (T t : items) {
            roll -= Math.max(0, weight.applyAsInt(t));
            if (roll < 0) {
                return t;
            }
        }
        return items.get(items.size() - 1);
    }
}
