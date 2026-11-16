package br.rustland.killmoney;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Guarda o ultimo dia coletado de cada jogador (arquivo daily-data.yml). */
final class DailyStore {

    static final class Progress {
        final int day;
        final LocalDate date;

        Progress(int day, LocalDate date) {
            this.day = day;
            this.date = date;
        }
    }

    private final Map<UUID, Progress> map = new HashMap<>();
    private boolean dirty;

    synchronized Progress get(UUID id) {
        return map.get(id);
    }

    synchronized void put(UUID id, int day, LocalDate date) {
        map.put(id, new Progress(day, date));
        dirty = true;
    }

    synchronized void load(File file) {
        map.clear();
        dirty = false;
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                int day = y.getInt(key + ".day");
                String date = y.getString(key + ".date", "");
                map.put(id, new Progress(day, LocalDate.parse(date)));
            } catch (RuntimeException ignored) {
                // linha invalida: ignora
            }
        }
    }

    /** Salva so se houve mudanca (ou se force = true). Retorna false se deu erro. */
    boolean save(File file, boolean force) {
        Map<UUID, Progress> copy;
        synchronized (this) {
            if (!dirty && !force) {
                return true;
            }
            copy = new HashMap<>(map);
            dirty = false;
        }
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Progress> e : copy.entrySet()) {
            y.set(e.getKey() + ".day", e.getValue().day);
            y.set(e.getKey() + ".date", e.getValue().date.toString());
        }
        try {
            y.save(file);
            return true;
        } catch (IOException ex) {
            synchronized (this) {
                dirty = true;
            }
            return false;
        }
    }
}
