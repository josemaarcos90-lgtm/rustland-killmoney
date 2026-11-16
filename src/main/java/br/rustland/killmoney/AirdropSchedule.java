package br.rustland.killmoney;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Horarios do air drop (logica pura, sem Bukkit). */
final class AirdropSchedule {

    /** Se o servidor estava parado/vazio no minuto exato, ainda dispara ate este tempo depois. */
    static final int CATCHUP_MINUTES = 5;

    private AirdropSchedule() {
    }

    static List<LocalTime> parseTimes(List<String> raw) {
        List<LocalTime> out = new ArrayList<>();
        for (String s : raw) {
            try {
                out.add(LocalTime.parse(s.trim()));
            } catch (RuntimeException ignored) {
                // horario invalido: ignora
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Chave do horario que deve disparar agora (ex.: 2026-11-16T18:00) ou null. */
    static String dueSlot(LocalDateTime now, List<LocalTime> times, String lastKey) {
        for (LocalTime t : times) {
            LocalDateTime slot = now.toLocalDate().atTime(t);
            if (!now.isBefore(slot) && now.isBefore(slot.plusMinutes(CATCHUP_MINUTES))) {
                String key = slot.toString();
                if (!key.equals(lastKey)) {
                    return key;
                }
            }
        }
        return null;
    }

    /** Proximo horario do dia (ou o primeiro do dia seguinte). */
    static LocalTime next(LocalDateTime now, List<LocalTime> times) {
        if (times.isEmpty()) {
            return null;
        }
        for (LocalTime t : times) {
            if (t.isAfter(now.toLocalTime())) {
                return t;
            }
        }
        return times.get(0);
    }
}
