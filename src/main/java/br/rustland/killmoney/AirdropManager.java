package br.rustland.killmoney;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Air drops: baus que caem em horarios fixos, com 4 raridades.
 * Mostra aviso (chat + titulo com coordenadas) e expoe placeholders para o scoreboard do TAB.
 */
final class AirdropManager implements Listener {

    static final List<String> TIERS = List.of("common", "uncommon", "rare", "epic");
    private static final int CHEST_SLOTS = 27;

    /** Dados de uma raridade, lidos do airdrop.yml. */
    static final class TierInfo {
        final String id;
        final String name;
        final String color;
        final int weight;
        final int[] rolls;
        final List<LootEntry> entries;
        final List<String> openCommands;

        TierInfo(String id, String name, String color, int weight, int[] rolls,
                 List<LootEntry> entries, List<String> openCommands) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.weight = weight;
            this.rolls = rolls;
            this.entries = entries;
            this.openCommands = openCommands;
        }
    }

    /** O airdrop que esta ativo agora. */
    static final class Drop {
        final World world;
        final int x;
        final int y;
        final int z;
        final String tier;
        final long endMs;
        volatile boolean opened;

        Drop(World world, int x, int y, int z, String tier, long endMs) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tier = tier;
            this.endMs = endMs;
        }

        boolean matches(Location l) {
            return l != null && l.getWorld() != null && l.getWorld().equals(world)
                    && l.getBlockX() == x && l.getBlockY() == y && l.getBlockZ() == z;
        }
    }

    private final KillMoneyPlugin plugin;
    private final Random random = new Random();
    private final File configFile;
    private final File dataFile;

    private YamlConfiguration cfg;
    private volatile Map<String, TierInfo> tiers = new LinkedHashMap<>();
    private volatile List<LocalTime> times = List.of();
    private volatile ZoneId zone = ZoneId.of("America/Sao_Paulo");
    private volatile Drop active;
    private boolean pending;
    private String lastSlot = "";

    AirdropManager(KillMoneyPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "airdrop.yml");
        this.dataFile = new File(plugin.getDataFolder(), "airdrop-data.yml");
    }

    // ------------------------------------------------------------ config

    void reload() {
        if (!configFile.exists()) {
            plugin.saveResource("airdrop.yml", false);
        }
        YamlConfiguration c = YamlConfiguration.loadConfiguration(configFile);
        cfg = c;
        try {
            zone = ZoneId.of(c.getString("timezone", "America/Sao_Paulo"));
        } catch (DateTimeException ex) {
            plugin.getLogger().warning("timezone invalido no airdrop.yml, usando America/Sao_Paulo");
            zone = ZoneId.of("America/Sao_Paulo");
        }
        times = AirdropSchedule.parseTimes(c.getStringList("times"));

        Map<String, TierInfo> map = new LinkedHashMap<>();
        for (String id : TIERS) {
            String base = "tiers." + id + ".";
            List<LootEntry> entries = new ArrayList<>();
            for (String spec : c.getStringList(base + "loot")) {
                try {
                    entries.add(LootEntry.parse(spec));
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Loot invalido em " + id + ": '" + spec + "' (" + ex.getMessage() + ")");
                }
            }
            int[] rolls;
            try {
                rolls = LootEntry.parseRange(c.getString(base + "rolls", "3-5"));
            } catch (RuntimeException ex) {
                rolls = new int[] {3, 5};
            }
            map.put(id, new TierInfo(
                    id,
                    c.getString(base + "name", id.toUpperCase()),
                    c.getString(base + "color", "&f"),
                    Math.max(0, c.getInt(base + "weight", 0)),
                    rolls,
                    entries,
                    c.getStringList(base + "open-commands")));
        }
        tiers = map;
    }

    void start() {
        reload();
        if (dataFile.exists()) {
            lastSlot = YamlConfiguration.loadConfiguration(dataFile).getString("last", "");
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new AirdropExpansion(this).register();
        } else {
            plugin.getLogger().warning("PlaceholderAPI nao encontrado: o scoreboard nao vai mostrar o airdrop.");
        }
    }

    void stop() {
        end(null);
    }

    private void saveLast() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("last", lastSlot);
        try {
            y.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Nao consegui salvar airdrop-data.yml: " + ex.getMessage());
        }
    }

    private static Component plain(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    private static String mmss(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private String fmt(String text, Drop d, TierInfo t, long minutes) {
        return text
                .replace("%tier%", t.name)
                .replace("%color%", t.color)
                .replace("%x%", String.valueOf(d.x))
                .replace("%y%", String.valueOf(d.y))
                .replace("%z%", String.valueOf(d.z))
                .replace("%minutes%", String.valueOf(minutes))
                .replace("%time%", mmss(d.endMs - System.currentTimeMillis()));
    }

    // -------------------------------------------------------------- tick

    private void tick() {
        Drop d = active;
        if (d != null) {
            beam(d);
            if (System.currentTimeMillis() >= d.endMs) {
                TierInfo t = tiers.get(d.tier);
                end(fmt(cfg.getString("messages.ended-time", ""), d, t, 0));
            }
            return;
        }
        if (pending || !cfg.getBoolean("enabled", true)) {
            return;
        }
        String slot = AirdropSchedule.dueSlot(LocalDateTime.now(zone), times, lastSlot);
        if (slot == null) {
            return;
        }
        if (Bukkit.getOnlinePlayers().size() < Math.max(1, cfg.getInt("min-players", 1))) {
            return;
        }
        lastSlot = slot;
        saveLast();
        String err = trySpawn(null);
        if (err != null) {
            plugin.getLogger().warning("Airdrop: " + err);
        }
    }

    private void beam(Drop d) {
        double cx = d.x + 0.5;
        double cz = d.z + 0.5;
        for (int h = 1; h <= 40; h += 2) {
            d.world.spawnParticle(Particle.END_ROD, cx, d.y + h, cz, 1, 0.0, 0.0, 0.0, 0.0, null, true);
        }
    }

    // ------------------------------------------------------------- spawn

    /** Inicia um airdrop. Retorna null se comecou, ou o motivo se nao deu. */
    String trySpawn(String forcedTier) {
        if (active != null || pending) {
            return "Ja existe um airdrop ativo ou sendo criado.";
        }
        String worldName = cfg.getString("world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return "Mundo '" + worldName + "' nao encontrado.";
        }
        String tier = forcedTier;
        if (tier == null) {
            TierInfo picked = LootEntry.pick(new ArrayList<>(tiers.values()), t -> t.weight, random);
            if (picked == null) {
                return "Nenhuma raridade com chance maior que 0 no airdrop.yml.";
            }
            tier = picked.id;
        }
        pending = true;
        findSpot(world, tier, 0);
        return null;
    }

    private void findSpot(World world, String tierId, int attempt) {
        if (attempt >= 25) {
            pending = false;
            plugin.getLogger().warning("Airdrop: nao achei um local bom depois de 25 tentativas.");
            return;
        }
        double min = Math.max(0, cfg.getDouble("radius-min", 150));
        double max = Math.max(min + 1, cfg.getDouble("radius-max", 1500));
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = Math.sqrt(random.nextDouble() * (max * max - min * min) + min * min);
        Location center = world.getSpawnLocation();
        int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
        int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);

        if (!world.getWorldBorder().isInside(new Location(world, x, 64, z))) {
            findSpot(world, tierId, attempt + 1);
            return;
        }
        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(plugin, () -> checkSpot(world, x, z, tierId, attempt))
        ).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> findSpot(world, tierId, attempt + 1));
            return null;
        });
    }

    private void checkSpot(World world, int x, int z, String tierId, int attempt) {
        Block top = world.getHighestBlockAt(x, z);
        Block spot = top.getRelative(BlockFace.UP);
        boolean bad = top.isLiquid()
                || top.getType().name().endsWith("LEAVES")
                || !spot.getType().isAir();
        if (bad) {
            findSpot(world, tierId, attempt + 1);
            return;
        }
        place(world, spot, tierId);
    }

    private void place(World world, Block spot, String tierId) {
        TierInfo tier = tiers.get(tierId);
        spot.setType(Material.CHEST);
        BlockState state = spot.getState();
        if (state instanceof Chest chest) {
            fill(chest.getBlockInventory(), tier);
        }
        long minutes = Math.max(1, cfg.getInt("despawn-minutes", 30));
        Drop d = new Drop(world, spot.getX(), spot.getY(), spot.getZ(), tierId,
                System.currentTimeMillis() + minutes * 60_000L);
        active = d;
        pending = false;
        announce(d, tier, minutes);
    }

    private void fill(Inventory inv, TierInfo tier) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < CHEST_SLOTS; i++) {
            slots.add(i);
        }
        Collections.shuffle(slots, random);
        int rolls = LootEntry.roll(tier.rolls, random);
        int used = 0;
        for (int i = 0; i < rolls && used < slots.size(); i++) {
            LootEntry e = LootEntry.pick(tier.entries, entry -> entry.weight, random);
            if (e == null) {
                break;
            }
            Material m = Material.matchMaterial(e.material);
            if (m == null || m.isAir()) {
                plugin.getLogger().warning("Material invalido no airdrop.yml: " + e.material);
                continue;
            }
            int amount = Math.min(e.rollAmount(random), m.getMaxStackSize());
            ItemStack stack = new ItemStack(m, amount);
            for (Map.Entry<String, Integer> en : e.enchants.entrySet()) {
                Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(en.getKey()));
                if (ench == null) {
                    plugin.getLogger().warning("Encantamento invalido no airdrop.yml: " + en.getKey());
                    continue;
                }
                stack.addUnsafeEnchantment(ench, en.getValue());
            }
            inv.setItem(slots.get(used), stack);
            used++;
        }
    }

    private void announce(Drop d, TierInfo t, long minutes) {
        for (String line : cfg.getStringList("messages.announce")) {
            Bukkit.broadcast(plain(fmt(line, d, t, minutes)));
        }
        Title title = Title.title(
                plain(fmt(cfg.getString("messages.title", ""), d, t, minutes)),
                plain(fmt(cfg.getString("messages.subtitle", ""), d, t, minutes)),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(1)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
        }
    }

    // --------------------------------------------------------------- fim

    void end(String message) {
        Drop d = active;
        if (d == null) {
            return;
        }
        active = null;
        Block b = d.world.getBlockAt(d.x, d.y, d.z);
        if (b.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
        }
        if (b.getType() == Material.CHEST) {
            b.setType(Material.AIR);
        }
        if (message != null && !message.isEmpty()) {
            Bukkit.broadcast(plain(message));
        }
    }

    // ----------------------------------------------------------- eventos

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        Drop d = active;
        if (d == null || !d.matches(event.getInventory().getLocation())) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player) || d.opened) {
            return;
        }
        d.opened = true;
        TierInfo t = tiers.get(d.tier);
        for (String command : t.openCommands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }
        Bukkit.broadcast(plain(fmt(cfg.getString("messages.opened", "")
                .replace("%player%", player.getName()), d, t, 0)));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Drop d = active;
        if (d == null || !d.matches(event.getInventory().getLocation())) {
            return;
        }
        if (event.getInventory().isEmpty()) {
            TierInfo t = tiers.get(d.tier);
            end(fmt(cfg.getString("messages.ended-empty", ""), d, t, 0));
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Drop d = active;
        if (d != null && d.matches(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        Drop d = active;
        if (d != null) {
            event.blockList().removeIf(b -> d.matches(b.getLocation()));
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        Drop d = active;
        if (d != null) {
            event.blockList().removeIf(b -> d.matches(b.getLocation()));
        }
    }

    // ---------------------------------------------------------- comandos

    boolean onCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || !sender.hasPermission("killmoney.admin")) {
            status(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "spawn": {
                String tier = args.length > 1 ? args[1].toLowerCase() : null;
                if (tier != null && !TIERS.contains(tier)) {
                    sender.sendMessage("Raridade invalida. Use: common, uncommon, rare ou epic.");
                    return true;
                }
                String err = trySpawn(tier);
                sender.sendMessage(err == null ? "Airdrop sendo criado..." : err);
                return true;
            }
            case "end":
                if (active == null) {
                    sender.sendMessage("Nenhum airdrop ativo.");
                } else {
                    end("&7O airdrop foi encerrado por um administrador.");
                    sender.sendMessage("Airdrop encerrado.");
                }
                return true;
            case "reload":
                reload();
                sender.sendMessage("airdrop.yml recarregado.");
                return true;
            default:
                status(sender);
                return true;
        }
    }

    private void status(CommandSender sender) {
        Drop d = active;
        if (d == null) {
            LocalTime next = AirdropSchedule.next(LocalDateTime.now(zone), times);
            sender.sendMessage(plain(cfg.getString("messages.none", "")
                    .replace("%next%", next == null ? "-" : next.toString())));
            return;
        }
        TierInfo t = tiers.get(d.tier);
        sender.sendMessage(plain(fmt(cfg.getString("messages.active", ""), d, t, 0)));
    }

    // ------------------------------------------------------ placeholders

    /** Valor de um placeholder %rustland_<key>% (pode ser chamado fora da thread principal). */
    String placeholder(String key) {
        Drop d = active;
        if ("airdrop_active".equals(key)) {
            return String.valueOf(d != null);
        }
        if ("airdrop_next".equals(key)) {
            LocalTime n = AirdropSchedule.next(LocalDateTime.now(zone), times);
            return n == null ? "" : n.toString();
        }
        if (d == null) {
            return key.startsWith("airdrop_") ? "" : null;
        }
        TierInfo t = tiers.get(d.tier);
        switch (key) {
            case "airdrop_tier":
                return t.name;
            case "airdrop_tier_color":
                return t.color;
            case "airdrop_x":
                return String.valueOf(d.x);
            case "airdrop_y":
                return String.valueOf(d.y);
            case "airdrop_z":
                return String.valueOf(d.z);
            case "airdrop_coords":
                return d.x + " " + d.y + " " + d.z;
            case "airdrop_time_left":
                return mmss(d.endMs - System.currentTimeMillis());
            case "airdrop_status":
                return d.opened ? "Aberto" : "Fechado";
            default:
                return null;
        }
    }
}
