package br.rustland.killmoney;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Kit inicial de PvP: automatico no primeiro login e /kit com tempo de espera (para quem morreu). */
final class KitManager implements Listener {

    private final KillMoneyPlugin plugin;
    private final File configFile;
    private final File dataFile;
    private final Map<UUID, Long> last = new HashMap<>();
    private YamlConfiguration cfg;

    KitManager(KillMoneyPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "kit.yml");
        this.dataFile = new File(plugin.getDataFolder(), "kit-data.yml");
    }

    void start() {
        reload();
        if (dataFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
            for (String key : y.getKeys(false)) {
                try {
                    last.put(UUID.fromString(key), y.getLong(key));
                } catch (IllegalArgumentException ignored) {
                    // chave invalida, ignora
                }
            }
        }
    }

    void reload() {
        if (!configFile.exists()) {
            plugin.saveResource("kit.yml", false);
        }
        YamlConfiguration c = YamlConfiguration.loadConfiguration(configFile);
        InputStream in = plugin.getResource("kit.yml");
        if (in != null) {
            boolean changed = false;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(r);
                for (String key : def.getKeys(true)) {
                    if (!def.isConfigurationSection(key) && !c.contains(key)) {
                        c.set(key, def.get(key));
                        changed = true;
                    }
                }
            } catch (IOException ex) {
                plugin.getLogger().warning("Nao consegui ler o kit.yml padrao: " + ex.getMessage());
            }
            if (changed) {
                try {
                    c.save(configFile);
                } catch (IOException ex) {
                    plugin.getLogger().warning("Nao consegui salvar kit.yml: " + ex.getMessage());
                }
            }
        }
        cfg = c;
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : last.entrySet()) {
            y.set(e.getKey().toString(), e.getValue());
        }
        try {
            y.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Nao consegui salvar kit-data.yml: " + ex.getMessage());
        }
    }

    private static net.kyori.adventure.text.Component plain(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    private String msg(String key) {
        String v = cfg.getString("messages." + key, "");
        return v == null ? "" : v;
    }

    private static String duration(long ms) {
        long min = Math.max(1, (ms + 59_999) / 60_000);
        return min >= 60 ? (min / 60) + "h " + (min % 60) + "min" : min + " min";
    }

    /** Monta um item a partir de "MATERIAL [quantidade] [ENCANTO=nivel,...]". Null se invalido. */
    private ItemStack parse(String spec) {
        String[] t = spec.trim().split("\\s+");
        Material m = Material.matchMaterial(t[0]);
        if (m == null || m.isAir()) {
            plugin.getLogger().warning("kit.yml: material invalido '" + t[0] + "'");
            return null;
        }
        int amount = 1;
        String ench = null;
        for (int i = 1; i < t.length; i++) {
            if (t[i].contains("=")) {
                ench = t[i];
            } else {
                try {
                    amount = Integer.parseInt(t[i]);
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("kit.yml: quantidade invalida em '" + spec + "'");
                }
            }
        }
        ItemStack stack = new ItemStack(m, Math.max(1, Math.min(amount, m.getMaxStackSize())));
        if (ench != null) {
            for (String part : ench.split(",")) {
                String[] kv = part.split("=");
                Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(kv[0].toLowerCase()));
                if (e == null || kv.length < 2) {
                    plugin.getLogger().warning("kit.yml: encantamento invalido '" + part + "'");
                    continue;
                }
                try {
                    stack.addUnsafeEnchantment(e, Integer.parseInt(kv[1]));
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("kit.yml: nivel invalido '" + part + "'");
                }
            }
        }
        return stack;
    }

    private void give(Player player) {
        PlayerInventory inv = player.getInventory();
        List<ItemStack> rest = new ArrayList<>();
        for (String spec : cfg.getStringList("items")) {
            ItemStack s = parse(spec);
            if (s == null) {
                continue;
            }
            String n = s.getType().name();
            if (n.endsWith("_HELMET") && inv.getHelmet() == null) {
                inv.setHelmet(s);
            } else if (n.endsWith("_CHESTPLATE") && inv.getChestplate() == null) {
                inv.setChestplate(s);
            } else if (n.endsWith("_LEGGINGS") && inv.getLeggings() == null) {
                inv.setLeggings(s);
            } else if (n.endsWith("_BOOTS") && inv.getBoots() == null) {
                inv.setBoots(s);
            } else {
                rest.add(s);
            }
        }
        for (ItemStack s : rest) {
            for (ItemStack left : inv.addItem(s).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
    }

    /** Entrega o kit se o tempo de espera passou. Retorna null se entregou, ou a mensagem de erro. */
    String claim(Player player, boolean ignoreCooldown) {
        long now = System.currentTimeMillis();
        long wait = Math.max(0, cfg.getLong("cooldown-minutes", 180)) * 60_000L;
        Long prev = last.get(player.getUniqueId());
        if (!ignoreCooldown && prev != null && now - prev < wait) {
            return msg("cooldown").replace("%time%", duration(wait - (now - prev)));
        }
        give(player);
        last.put(player.getUniqueId(), now);
        save();
        return null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!cfg.getBoolean("give-on-first-join", true) || p.hasPlayedBefore()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && claim(p, true) == null) {
                p.sendMessage(plain(msg("first-join")));
            }
        }, 40L);
    }

    boolean onCommand(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("killmoney.admin")) {
            reload();
            sender.sendMessage("kit.yml recarregado.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Esse comando e so para jogadores.");
            return true;
        }
        String err = claim(player, false);
        player.sendMessage(plain(err == null ? msg("received") : err));
        return true;
    }
}
