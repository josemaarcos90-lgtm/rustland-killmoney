package br.rustland.killmoney;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

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
        final double price;
        final String icon;

        TierInfo(String id, String name, String color, int weight, int[] rolls,
                 List<LootEntry> entries, List<String> openCommands, double price, String icon) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.weight = weight;
            this.rolls = rolls;
            this.entries = entries;
            this.openCommands = openCommands;
            this.price = price;
            this.icon = icon;
        }
    }

    /** O airdrop atual: "caindo" (comprado, esperando) ou "no chao" (landed). */
    static final class Drop {
        final World world;
        final String tier;
        final UUID buyerId;     // null = airdrop automatico/admin
        final String buyer;
        final double price;
        final long landMs;
        volatile int x;
        volatile int y;
        volatile int z;
        volatile long endMs;
        volatile boolean landed;
        volatile boolean landing;
        volatile boolean opened;
        boolean warned;

        Drop(World world, String tier, UUID buyerId, String buyer, double price, long landMs,
             int x, int y, int z) {
            this.world = world;
            this.tier = tier;
            this.buyerId = buyerId;
            this.buyer = buyer;
            this.price = price;
            this.landMs = landMs;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean matches(Location l) {
            return landed && l != null && l.getWorld() != null && l.getWorld().equals(world)
                    && l.getBlockX() == x && l.getBlockY() == y && l.getBlockZ() == z;
        }
    }

    private static final class BuyHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Confirm {
        final String tier;
        final long untilMs;

        Confirm(String tier, long untilMs) {
            this.tier = tier;
            this.untilMs = untilMs;
        }
    }

    private static final int[] BUY_SLOTS = {10, 12, 14, 16};

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
    private final VaultHook vault;
    private final Map<UUID, Confirm> confirms = new HashMap<>();
    private int fx;

    AirdropManager(KillMoneyPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "airdrop.yml");
        this.dataFile = new File(plugin.getDataFolder(), "airdrop-data.yml");
        this.vault = new VaultHook(plugin);
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
                    c.getStringList(base + "open-commands"),
                    Math.max(0, c.getDouble(base + "price", 0)),
                    c.getString(base + "icon", "CHEST")));
        }
        tiers = map;
    }

    void start() {
        reload();
        if (dataFile.exists()) {
            lastSlot = YamlConfiguration.loadConfiguration(dataFile).getString("last", "");
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::effects, 40L, 5L);
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

    private static String money(double v) {
        return java.text.NumberFormat.getIntegerInstance(Locale.forLanguageTag("pt-BR")).format(Math.round(v));
    }

    private String fmt(String text, Drop d, TierInfo t, long minutes) {
        long left = (d.landed ? d.endMs : d.landMs) - System.currentTimeMillis();
        return text
                .replace("%tier%", t.name)
                .replace("%color%", t.color)
                .replace("%x%", String.valueOf(d.x))
                .replace("%y%", String.valueOf(d.y))
                .replace("%z%", String.valueOf(d.z))
                .replace("%minutes%", String.valueOf(minutes))
                .replace("%buyer%", d.buyer == null ? "" : d.buyer)
                .replace("%price%", money(d.price))
                .replace("%time%", mmss(left));
    }

    private String msg(String key) {
        return cfg.getString("messages." + key, "");
    }

    // -------------------------------------------------------------- tick

    private void tick() {
        Drop d = active;
        if (d != null) {
            long now = System.currentTimeMillis();
            if (!d.landed) {
                incomingTick(d, now);
            } else if (now >= d.endMs) {
                end(fmt(msg("ended-time"), d, tiers.get(d.tier), 0));
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

    /** Roda 1x por segundo enquanto o airdrop comprado ainda esta caindo. */
    private void incomingTick(Drop d, long now) {
        long left = d.landMs - now;
        TierInfo t = tiers.get(d.tier);
        if (left <= 0) {
            if (!d.landing) {
                d.landing = true;
                startLanding(d);
            }
            return;
        }
        if (!d.warned && left <= 60_000L) {
            d.warned = true;
            for (String line : cfg.getStringList("messages.warn-1min")) {
                Bukkit.broadcast(plain(fmt(line, d, t, 0)));
            }
        }
        boolean last10 = left <= 10_000L;
        if (last10 || (left / 1000) % 2 == 0) {
            heartbeat(d, last10 ? 1.0f : 0.7f);
        }
    }

    private void heartbeat(Drop d, float volume) {
        double range = Math.max(20, cfg.getDouble("effects.sound-range", 150));
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            if (!d.world.equals(l.getWorld())) {
                continue;
            }
            double dx = l.getX() - d.x;
            double dz = l.getZ() - d.z;
            if (dx * dx + dz * dz <= range * range) {
                p.playSound(l, Sound.ENTITY_WARDEN_HEARTBEAT, volume, 1.0f);
            }
        }
    }

    // --------------------------------------------------------- efeitos

    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(255, 20, 20), 1.6f);
    private static final Particle.DustOptions DARK = new Particle.DustOptions(Color.fromRGB(140, 0, 0), 1.9f);

    /** Roda a cada 5 ticks: feixe vermelho, anel no chao e, nos ultimos segundos, a bola de fogo caindo. */
    private void effects() {
        Drop d = active;
        if (d == null) {
            return;
        }
        fx++;
        World w = d.world;
        double cx = d.x + 0.5;
        double cz = d.z + 0.5;
        int height = Math.max(10, cfg.getInt("effects.beam-height", 60));
        long left = d.landMs - System.currentTimeMillis();
        boolean incoming = !d.landed;

        // feixe: escuro e fino enquanto cai, vermelho vivo depois de pousar
        for (int h = 1 + (fx % 2); h <= height; h += 2) {
            w.spawnParticle(Particle.DUST, cx, d.y + h, cz, 1, 0.12, 0.0, 0.12, 0.0, incoming ? DARK : RED, true);
        }
        // anel girando no chao (maior e mais lento enquanto cai, menor e com fogo depois)
        double radius = incoming ? 3.5 : 2.5;
        int points = 20;
        double spin = fx * 0.35;
        for (int i = 0; i < points; i++) {
            double a = spin + (Math.PI * 2 * i / points);
            double px = cx + Math.cos(a) * radius;
            double pz = cz + Math.sin(a) * radius;
            if (incoming) {
                w.spawnParticle(Particle.DUST, px, d.y + 0.2, pz, 1, 0.0, 0.0, 0.0, 0.0, RED, true);
            } else if (i % 2 == 0) {
                w.spawnParticle(Particle.FLAME, px, d.y + 0.2, pz, 1, 0.0, 0.02, 0.0, 0.0, null, true);
            }
        }
        if (!incoming) {
            if (fx % 4 == 0) {
                w.spawnParticle(Particle.SMOKE, cx, d.y + 1.0, cz, 6, 0.3, 0.4, 0.3, 0.02, null, true);
            }
            return;
        }
        // nos ultimos 10 segundos uma bola de fogo desce do ceu ate o ponto
        if (left > 0 && left <= 10_000L) {
            double frac = left / 10_000.0;
            double fy = d.y + 1 + 80.0 * frac;
            w.spawnParticle(Particle.FLAME, cx, fy, cz, 8, 0.35, 0.35, 0.35, 0.02, null, true);
            w.spawnParticle(Particle.SMOKE, cx, fy + 1.2, cz, 6, 0.3, 0.6, 0.3, 0.01, null, true);
            w.spawnParticle(Particle.DUST, cx, fy, cz, 6, 0.4, 0.4, 0.4, 0.0, RED, true);
        }
    }

    // ------------------------------------------------------------- spawn

    /** Inicia um airdrop automatico/admin. Retorna null se comecou, ou o motivo se nao deu. */
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
        Block spot = surface(world, x, z);
        if (spot == null) {
            findSpot(world, tierId, attempt + 1);
            return;
        }
        long minutes = Math.max(1, cfg.getInt("despawn-minutes", 30));
        Drop d = new Drop(world, tierId, null, null, 0, System.currentTimeMillis(),
                spot.getX(), spot.getY(), spot.getZ());
        pending = false;
        active = d;
        placeChest(d, spot, minutes);
    }

    /** Bloco livre em cima da superficie da coluna (x,z), ou null se for agua, folhas ou estiver ocupado. */
    private Block surface(World world, int x, int z) {
        if (!world.getWorldBorder().isInside(new Location(world, x, 64, z))) {
            return null;
        }
        Block top = world.getHighestBlockAt(x, z);
        Block spot = top.getRelative(BlockFace.UP);
        boolean bad = top.isLiquid()
                || top.getType().name().endsWith("LEAVES")
                || !spot.getType().isAir();
        return bad ? null : spot;
    }

    /** Procura em espiral a partir de (cx,cz) um lugar valido, ate o raio dado. */
    private Block nearestSurface(World world, int cx, int cz, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    Block b = surface(world, cx + dx, cz + dz);
                    if (b != null) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    /** Coloca o bau, enche e anuncia. O Drop ja deve estar em "active". */
    private void placeChest(Drop d, Block spot, long minutes) {
        TierInfo tier = tiers.get(d.tier);
        spot.setType(Material.CHEST);
        BlockState state = spot.getState();
        if (state instanceof Chest chest) {
            fill(chest.getBlockInventory(), tier);
        }
        d.x = spot.getX();
        d.y = spot.getY();
        d.z = spot.getZ();
        d.endMs = System.currentTimeMillis() + minutes * 60_000L;
        d.landed = true;
        Location loc = new Location(d.world, d.x + 0.5, d.y, d.z + 0.5);
        d.world.strikeLightningEffect(loc);
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
                plain(fmt(msg("title"), d, t, minutes)),
                plain(fmt(msg("subtitle"), d, t, minutes)),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(1)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
        }
    }

    // ------------------------------------------------------------ compra

    /** Compra um airdrop para o jogador. Retorna null se deu certo, ou a mensagem de erro (com &). */
    String buy(Player player, String tierId) {
        if (!cfg.getBoolean("buy.enabled", true)) {
            return msg("buy-disabled");
        }
        TierInfo t = tiers.get(tierId);
        if (t == null || t.price <= 0) {
            return msg("buy-unavailable");
        }
        if (active != null || pending) {
            return msg("buy-busy");
        }
        World world = Bukkit.getWorld(cfg.getString("world", "world"));
        if (world == null || !world.equals(player.getWorld())) {
            return msg("buy-wrong-world");
        }
        if (!vault.ready()) {
            return msg("buy-no-economy");
        }
        if (!vault.has(player, t.price)) {
            return msg("buy-no-money").replace("%price%", money(t.price));
        }
        Location loc = player.getLocation();
        Block spot = nearestSurface(world, loc.getBlockX(), loc.getBlockZ(), 8);
        if (spot == null) {
            return msg("buy-no-spot");
        }
        if (!vault.withdraw(player, t.price)) {
            return msg("buy-no-money").replace("%price%", money(t.price));
        }
        long delay = Math.max(1, cfg.getInt("buy.delay-minutes", 5));
        Drop d = new Drop(world, tierId, player.getUniqueId(), player.getName(), t.price,
                System.currentTimeMillis() + delay * 60_000L, spot.getX(), spot.getY(), spot.getZ());
        active = d;
        announceBought(d, t, delay);
        return null;
    }

    private void announceBought(Drop d, TierInfo t, long delay) {
        for (String line : cfg.getStringList("messages.bought")) {
            Bukkit.broadcast(plain(fmt(line, d, t, delay)));
        }
        Title title = Title.title(
                plain(fmt(msg("bought-title"), d, t, delay)),
                plain(fmt(msg("bought-subtitle"), d, t, delay)),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(7), Duration.ofSeconds(1)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.8f);
        }
    }

    /** O tempo de espera acabou: carrega o chunk e coloca o bau. */
    private void startLanding(Drop d) {
        d.world.getChunkAtAsync(d.x >> 4, d.z >> 4).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(plugin, () -> land(d))
        ).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> land(d));
            return null;
        });
    }

    private void land(Drop d) {
        if (active != d) {
            return;
        }
        Block spot = nearestSurface(d.world, d.x, d.z, 3);
        if (spot == null) {
            plugin.getLogger().warning("Airdrop comprado nao achou lugar para cair em " + d.x + " " + d.z + ", reembolsando.");
            active = null;
            refund(d);
            Bukkit.broadcast(plain(fmt(msg("landing-failed"), d, tiers.get(d.tier), 0)));
            return;
        }
        placeChest(d, spot, Math.max(1, cfg.getInt("despawn-minutes", 30)));
    }

    private void refund(Drop d) {
        if (d.buyerId == null || d.price <= 0) {
            return;
        }
        boolean ok = vault.deposit(Bukkit.getOfflinePlayer(d.buyerId), d.price);
        if (!ok) {
            plugin.getLogger().warning("NAO consegui reembolsar " + d.price + " para " + d.buyer
                    + " (" + d.buyerId + "). Devolva manualmente: eco give " + d.buyer + " " + (long) d.price);
            return;
        }
        Player p = Bukkit.getPlayer(d.buyerId);
        if (p != null) {
            p.sendMessage(plain(fmt(msg("refunded"), d, tiers.get(d.tier), 0)));
        }
    }

    // --------------------------------------------------------------- menu

    private static Component item(String legacy) {
        return plain("&r" + legacy).decoration(TextDecoration.ITALIC, false);
    }

    void openMenu(Player player) {
        BuyHolder holder = new BuyHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, plain(cfg.getString("buy.menu-title", "Comprar Airdrop")));
        holder.inventory = inv;
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE, 1);
        ItemMeta pm = pane.getItemMeta();
        pm.displayName(plain(" "));
        pane.setItemMeta(pm);
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, pane);
        }
        long delay = Math.max(1, cfg.getInt("buy.delay-minutes", 5));
        for (int i = 0; i < TIERS.size(); i++) {
            TierInfo t = tiers.get(TIERS.get(i));
            Material m = Material.matchMaterial(t.icon);
            ItemStack stack = new ItemStack(m == null ? Material.CHEST : m, 1);
            ItemMeta meta = stack.getItemMeta();
            meta.displayName(item(t.color + "&l" + t.name));
            List<Component> lore = new ArrayList<>();
            for (String line : cfg.getStringList("buy.lore")) {
                lore.add(item(line.replace("%price%", money(t.price)).replace("%delay%", String.valueOf(delay))
                        .replace("%tier%", t.name).replace("%color%", t.color)));
            }
            meta.lore(lore);
            stack.setItemMeta(meta);
            inv.setItem(BUY_SLOTS[i], stack);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BuyHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        String tierId = null;
        for (int i = 0; i < BUY_SLOTS.length; i++) {
            if (BUY_SLOTS[i] == slot) {
                tierId = TIERS.get(i);
            }
        }
        if (tierId == null) {
            return;
        }
        TierInfo t = tiers.get(tierId);
        long now = System.currentTimeMillis();
        Confirm c = confirms.get(player.getUniqueId());
        if (c == null || !c.tier.equals(tierId) || now > c.untilMs) {
            long secs = Math.max(3, cfg.getInt("buy.confirm-seconds", 10));
            confirms.put(player.getUniqueId(), new Confirm(tierId, now + secs * 1000L));
            player.sendMessage(plain(msg("buy-confirm").replace("%tier%", t.name).replace("%color%", t.color)
                    .replace("%price%", money(t.price))));
            return;
        }
        confirms.remove(player.getUniqueId());
        player.closeInventory();
        String err = buy(player, tierId);
        if (err != null) {
            player.sendMessage(plain(err));
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof BuyHolder) {
            event.setCancelled(true);
        }
    }

    // --------------------------------------------------------------- fim

    void end(String message) {
        Drop d = active;
        if (d == null) {
            return;
        }
        active = null;
        if (!d.landed) {
            refund(d);
        } else {
            Block b = d.world.getBlockAt(d.x, d.y, d.z);
            if (b.getState() instanceof Chest chest) {
                chest.getBlockInventory().clear();
            }
            if (b.getType() == Material.CHEST) {
                b.setType(Material.AIR);
            }
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
        Bukkit.broadcast(plain(fmt(msg("opened").replace("%player%", player.getName()), d, t, 0)));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Drop d = active;
        if (d == null || !d.matches(event.getInventory().getLocation())) {
            return;
        }
        if (event.getInventory().isEmpty()) {
            TierInfo t = tiers.get(d.tier);
            end(fmt(msg("ended-empty"), d, t, 0));
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
        if (args.length > 0 && (args[0].equalsIgnoreCase("buy") || args[0].equalsIgnoreCase("comprar"))) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Apenas jogadores podem comprar airdrops.");
                return true;
            }
            if (args.length == 1) {
                openMenu(player);
                return true;
            }
            String tier = args[1].toLowerCase();
            if (!TIERS.contains(tier)) {
                sender.sendMessage("Raridade invalida. Use: common, uncommon, rare ou epic.");
                return true;
            }
            String err = buy(player, tier);
            if (err != null) {
                player.sendMessage(plain(err));
            }
            return true;
        }
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
                    sender.sendMessage("Airdrop encerrado (se era comprado e ainda nao tinha caido, foi reembolsado).");
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
            sender.sendMessage(plain(msg("none").replace("%next%", next == null ? "-" : next.toString())));
            return;
        }
        TierInfo t = tiers.get(d.tier);
        sender.sendMessage(plain(fmt(msg(d.landed ? "active" : "status-incoming"), d, t, 0)));
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
                return mmss((d.landed ? d.endMs : d.landMs) - System.currentTimeMillis());
            case "airdrop_time_label":
                return d.landed ? "Some em" : "Cai em";
            case "airdrop_phase":
                return d.landed ? "landed" : "incoming";
            case "airdrop_buyer":
                return d.buyer == null ? "Servidor" : d.buyer;
            case "airdrop_status":
                return !d.landed ? "Caindo" : (d.opened ? "Aberto" : "Fechado");
            default:
                return null;
        }
    }
}
