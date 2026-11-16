package br.rustland.killmoney;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Recompensa diaria de 7 dias: menu /daily, coleta, aviso ao entrar. */
final class DailyManager implements Listener {

    static final int CYCLE = 7;
    private static final int MENU_SIZE = 27;
    private static final int SLOT_BASE = 9; // dia 1 = slot 10 ... dia 7 = slot 16

    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final KillMoneyPlugin plugin;
    private final File configFile;
    private final File dataFile;
    private final DailyStore store = new DailyStore();
    private YamlConfiguration cfg;
    private ZoneId zone = ZoneId.of("America/Sao_Paulo");

    DailyManager(KillMoneyPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "daily.yml");
        this.dataFile = new File(plugin.getDataFolder(), "daily-data.yml");
    }

    // ---------------------------------------------------------------- config

    void reload() {
        if (!configFile.exists()) {
            plugin.saveResource("daily.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(configFile);
        try {
            zone = ZoneId.of(cfg.getString("timezone", "America/Sao_Paulo"));
        } catch (DateTimeException ex) {
            plugin.getLogger().warning("timezone invalido no daily.yml, usando America/Sao_Paulo");
            zone = ZoneId.of("America/Sao_Paulo");
        }
    }

    void loadData() {
        store.load(dataFile);
    }

    void saveIfDirty() {
        if (!store.save(dataFile, false)) {
            plugin.getLogger().warning("Nao consegui salvar daily-data.yml");
        }
    }

    void saveNow() {
        if (!store.save(dataFile, true)) {
            plugin.getLogger().warning("Nao consegui salvar daily-data.yml");
        }
    }

    private String msg(String key) {
        return cfg.getString("messages." + key, "");
    }

    private static Component plain(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    private static Component item(String legacy) {
        return plain(legacy).decoration(TextDecoration.ITALIC, false);
    }

    // ----------------------------------------------------------------- estado

    private DailyState stateOf(UUID id) {
        DailyStore.Progress p = store.get(id);
        LocalDate today = LocalDate.now(zone);
        return DailyState.compute(p == null ? null : p.date, p == null ? 0 : p.day, today, CYCLE);
    }

    // ----------------------------------------------------------------- coletar

    boolean claim(Player player) {
        UUID id = player.getUniqueId();
        DailyState state = stateOf(id);
        if (state.claimableDay == 0) {
            player.sendMessage(plain(msg("already")));
            return false;
        }
        int day = state.claimableDay;
        List<String> commands = cfg.getStringList("rewards.day" + day + ".commands");
        if (commands.isEmpty()) {
            plugin.getLogger().warning("Dia " + day + " sem comandos no daily.yml");
        }
        for (String command : commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }
        store.put(id, day, LocalDate.now(zone));
        String reward = cfg.getString("rewards.day" + day + ".display", "");
        player.sendMessage(plain(msg("claimed")
                .replace("%day%", String.valueOf(day))
                .replace("%reward%", reward)));
        return true;
    }

    // ------------------------------------------------------------------- menu

    void openMenu(Player player) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, plain(cfg.getString("menu-title", "Recompensa Diaria")));
        holder.inventory = inv;
        fill(inv, player);
        player.openInventory(inv);
    }

    private void fill(Inventory inv, Player player) {
        DailyState state = stateOf(player.getUniqueId());

        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false);
        for (int i = 0; i < MENU_SIZE; i++) {
            inv.setItem(i, filler);
        }

        List<String> info = new ArrayList<>();
        info.add("&7Entre todo dia e colete para");
        info.add("&7manter a sequencia de &f" + CYCLE + " dias&7.");
        info.add("&7Se passar um dia sem coletar,");
        info.add("&7a sequencia volta ao dia 1.");
        inv.setItem(4, named(Material.CLOCK, "&6&lRecompensa Diaria", info, false));

        for (int day = 1; day <= CYCLE; day++) {
            String reward = cfg.getString("rewards.day" + day + ".display", "?");
            ItemStack stack;
            if (day <= state.claimedCount) {
                stack = named(Material.LIME_STAINED_GLASS_PANE, "&aDia " + day + " &7- coletado",
                        List.of("&7Recompensa: &f" + reward), false);
            } else if (day == state.claimableDay) {
                stack = named(iconOf(day), "&eDia " + day + " &7- &aclique para coletar",
                        List.of("&7Recompensa: &f" + reward), true);
            } else {
                stack = named(iconOf(day), "&7Dia " + day,
                        List.of("&7Recompensa: &f" + reward), false);
            }
            inv.setItem(SLOT_BASE + day, stack);
        }
    }

    private Material iconOf(int day) {
        Material m = Material.matchMaterial(cfg.getString("rewards.day" + day + ".icon", "GOLD_INGOT"));
        return m == null ? Material.GOLD_INGOT : m;
    }

    private static ItemStack named(Material material, String name, List<String> lore, boolean glow) {
        ItemStack stack = new ItemStack(material, 1);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(item(name));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(item(line));
        }
        meta.lore(lines);
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    // ---------------------------------------------------------------- eventos

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        DailyState state = stateOf(player.getUniqueId());
        if (state.claimableDay > 0 && event.getRawSlot() == SLOT_BASE + state.claimableDay) {
            claim(player);
            fill(event.getInventory(), player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && stateOf(player.getUniqueId()).claimableDay > 0) {
                player.sendMessage(plain(msg("reminder")));
            }
        }, 100L);
    }
}
