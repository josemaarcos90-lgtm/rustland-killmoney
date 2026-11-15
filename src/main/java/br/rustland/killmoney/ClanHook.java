package br.rustland.killmoney;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Liga com o zMClans por reflexao (assim o projeto compila sem o JAR dele).
 * Usa ZMClansAPI#getClanByPlayer(UUID) e ClanSnapshot#id().
 * Precisa rodar na thread principal (o evento de morte ja roda nela).
 */
final class ClanHook {

    private final Plugin owner;
    private Object api;
    private Method getClanByPlayer;
    private Method idMethod;
    private boolean warned;

    ClanHook(Plugin owner) {
        this.owner = owner;
        ensure();
    }

    private boolean ensure() {
        if (api != null) {
            return true;
        }
        Plugin zm = owner.getServer().getPluginManager().getPlugin("zMClans");
        if (zm == null || !zm.isEnabled()) {
            return false;
        }
        try {
            Object a = zm.getClass().getMethod("getAPI").invoke(zm);
            getClanByPlayer = a.getClass().getMethod("getClanByPlayer", UUID.class);
            api = a;
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            warnOnce("Nao consegui ligar com a API do zMClans: " + ex);
            return false;
        }
    }

    /** true se os dois jogadores estao no mesmo clan. */
    boolean sameClan(UUID a, UUID b) {
        if (!ensure()) {
            warnOnce("zMClans nao encontrado: kills dentro do mesmo clan NAO estao sendo bloqueadas.");
            return false;
        }
        try {
            UUID ca = clanId(a);
            if (ca == null) {
                return false;
            }
            UUID cb = clanId(b);
            return ca.equals(cb);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            warnOnce("Erro ao consultar o zMClans: " + ex);
            return false;
        }
    }

    private UUID clanId(UUID player) throws ReflectiveOperationException {
        Optional<?> opt = (Optional<?>) getClanByPlayer.invoke(api, player);
        if (opt.isEmpty()) {
            return null;
        }
        Object snapshot = opt.get();
        if (idMethod == null) {
            idMethod = snapshot.getClass().getMethod("id");
        }
        return (UUID) idMethod.invoke(snapshot);
    }

    private void warnOnce(String msg) {
        if (!warned) {
            warned = true;
            owner.getLogger().warning(msg);
        }
    }
}
