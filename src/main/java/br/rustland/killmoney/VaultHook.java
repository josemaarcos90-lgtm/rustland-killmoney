package br.rustland.killmoney;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * Economia (Vault) por reflexao, sem precisar do JAR do Vault para compilar.
 * Usa Economy#has, #withdrawPlayer e #depositPlayer.
 */
final class VaultHook {

    private final Plugin owner;
    private Object econ;
    private Method has;
    private Method withdraw;
    private Method deposit;
    private boolean warned;

    VaultHook(Plugin owner) {
        this.owner = owner;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    boolean ready() {
        if (econ != null) {
            return true;
        }
        Plugin vault = owner.getServer().getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            return false;
        }
        try {
            Class<?> economy = vault.getClass().getClassLoader().loadClass("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider rsp = owner.getServer().getServicesManager().getRegistration((Class) economy);
            if (rsp == null) {
                return false;
            }
            has = economy.getMethod("has", OfflinePlayer.class, double.class);
            withdraw = economy.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = economy.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            econ = rsp.getProvider();
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            if (!warned) {
                warned = true;
                owner.getLogger().warning("Nao consegui ligar com a economia do Vault: " + ex);
            }
            return false;
        }
    }

    boolean has(OfflinePlayer player, double amount) {
        if (!ready()) {
            return false;
        }
        try {
            return (Boolean) has.invoke(econ, player, amount);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    boolean withdraw(OfflinePlayer player, double amount) {
        return transact(withdraw, player, amount);
    }

    boolean deposit(OfflinePlayer player, double amount) {
        return transact(deposit, player, amount);
    }

    private boolean transact(Method method, OfflinePlayer player, double amount) {
        if (!ready()) {
            return false;
        }
        try {
            Object response = method.invoke(econ, player, amount);
            return (Boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            owner.getLogger().warning("Falha na transacao de economia: " + ex);
            return false;
        }
    }
}
