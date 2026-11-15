package br.rustland.killmoney;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KillMoneyPlugin extends JavaPlugin implements Listener {

    private RewardTracker tracker;
    private ClanHook clans;
    private File dataFile;

    private String command = "eco give %player% %amount%";
    private String message = "";
    private boolean blockSameClan = true;
    private Set<String> excludedWorlds = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        tracker = new RewardTracker(new ArrayList<>(), 0L);
        loadSettings();
        loadData();
        clans = new ClanHook(this);
        getServer().getPluginManager().registerEvents(this, this);
        long fiveMinutes = 20L * 60L * 5L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::saveData, fiveMinutes, fiveMinutes);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadSettings() {
        FileConfiguration c = getConfig();
        List<Long> amounts = c.getLongList("amounts");
        if (amounts.isEmpty()) {
            getLogger().warning("'amounts' vazio no config.yml: usando 200,100,75,50,25.");
            amounts = List.of(200L, 100L, 75L, 50L, 25L);
        }
        long resetMs = Math.max(0L, c.getLong("reset-minutes", 40L)) * 60_000L;
        tracker.configure(amounts, resetMs);
        command = c.getString("command", "eco give %player% %amount%");
        message = c.getString("message", "");
        blockSameClan = c.getBoolean("block-same-clan", true);
        excludedWorlds = new HashSet<>(c.getStringList("excluded-worlds"));
    }

    private void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : y.getKeys(false)) {
            tracker.load(key, y.getInt(key + ".count"), y.getLong(key + ".last"));
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, RewardTracker.Entry> en : tracker.snapshot(now).entrySet()) {
            y.set(en.getKey() + ".count", en.getValue().count());
            y.set(en.getKey() + ".last", en.getValue().last());
        }
        try {
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Nao consegui salvar data.yml: " + ex.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        if (excludedWorlds.contains(victim.getWorld().getName())) {
            return;
        }
        if (blockSameClan && clans.sameClan(killer.getUniqueId(), victim.getUniqueId())) {
            return;
        }

        long amount = tracker.reward(killer.getUniqueId(), victim.getUniqueId(), System.currentTimeMillis());
        if (amount <= 0L) {
            return;
        }

        String cmd = command
                .replace("%player%", killer.getName())
                .replace("%victim%", victim.getName())
                .replace("%amount%", String.valueOf(amount));
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);

        if (message != null && !message.isEmpty()) {
            String text = message
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%victim%", victim.getName());
            killer.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(text));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("killmoney.admin")) {
            sender.sendMessage("Sem permissao.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadSettings();
            sender.sendMessage("RustlandKillMoney recarregado.");
            return true;
        }
        sender.sendMessage("Uso: /killmoney reload");
        return true;
    }
}
