package br.rustland.killmoney;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

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
import java.util.concurrent.CompletableFuture;

/**
 * Lobby: cola uma construcao (.schem) no chao do mundo, nivelando o terreno ao redor,
 * e protege a area (sem PvP, sem quebrar/colocar blocos, sem monstros).
 */
final class LobbyManager implements Listener {

    static final List<String> VARIANTS = List.of("rustic", "stony");

    /** Raio (blocos) da praça redonda e da rampa que liga ela ao terreno. */
    private static final double INSIDE = 31.0;
    private static final int SKIRT = 6;
    private static final int OPS_PER_TICK = 6000;

    private record Op(int x, int y, int z, BlockData data) {
    }

    private static final class Zone {
        final String world;
        final int x;
        final int z;
        final double radius;

        Zone(String world, int x, int z, double radius) {
            this.world = world;
            this.x = x;
            this.z = z;
            this.radius = radius;
        }
    }

    private final KillMoneyPlugin plugin;
    private final File configFile;
    private final File dataFile;
    private final Map<UUID, Long> warned = new HashMap<>();
    private YamlConfiguration cfg;
    private volatile Zone zone;
    private boolean busy;

    LobbyManager(KillMoneyPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "lobby.yml");
        this.dataFile = new File(plugin.getDataFolder(), "lobby-data.yml");
    }

    // ------------------------------------------------------------ config

    void start() {
        reload();
        if (dataFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
            String w = y.getString("world", "");
            if (!w.isEmpty()) {
                zone = new Zone(w, y.getInt("x"), y.getInt("z"), y.getDouble("radius", INSIDE + SKIRT));
            }
        }
    }

    void reload() {
        if (!configFile.exists()) {
            plugin.saveResource("lobby.yml", false);
        }
        YamlConfiguration c = YamlConfiguration.loadConfiguration(configFile);
        InputStream in = plugin.getResource("lobby.yml");
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
                plugin.getLogger().warning("Nao consegui ler o lobby.yml padrao: " + ex.getMessage());
            }
            if (changed) {
                try {
                    c.save(configFile);
                } catch (IOException ex) {
                    plugin.getLogger().warning("Nao consegui salvar lobby.yml: " + ex.getMessage());
                }
            }
        }
        cfg = c;
    }

    private void saveZone() {
        Zone z = zone;
        if (z == null) {
            if (dataFile.exists() && !dataFile.delete()) {
                plugin.getLogger().warning("Nao consegui apagar lobby-data.yml");
            }
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", z.world);
        y.set("x", z.x);
        y.set("z", z.z);
        y.set("radius", z.radius);
        try {
            y.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Nao consegui salvar lobby-data.yml: " + ex.getMessage());
        }
    }

    private static net.kyori.adventure.text.Component plain(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    private String msg(String key) {
        String v = cfg.getString("messages." + key, "");
        return v == null ? "" : v;
    }

    // ------------------------------------------------------------ colagem

    /** Retorna null se comecou, ou o motivo de nao ter dado. */
    String paste(Player admin, String variant) {
        if (busy) {
            return "Ja existe uma colagem em andamento. Espere terminar.";
        }
        if (!VARIANTS.contains(variant)) {
            return "Versao invalida. Use: rustic ou stony.";
        }
        SchematicData s;
        try (InputStream in = plugin.getResource("lobby/" + variant + ".schem")) {
            if (in == null) {
                return "Arquivo da construcao nao encontrado dentro do plugin.";
            }
            s = SchematicData.read(in);
        } catch (IOException ex) {
            return "Erro ao ler a construcao: " + ex.getMessage();
        }

        World world = admin.getWorld();
        Location loc = admin.getLocation();
        int cx = loc.getBlockX();
        int cz = loc.getBlockZ();
        int gy = loc.getBlockY() - 1;
        if (!world.getBlockAt(cx, gy, cz).getType().isSolid()) {
            return "Fique em pe no chao (terra firme, sem voar) no centro de onde quer o lobby e use o comando de novo.";
        }
        if (gy + s.height + 2 > world.getMaxHeight() || gy < world.getMinHeight() + 8) {
            return "Altura invalida aqui para colar o lobby.";
        }

        busy = true;
        int half = (int) Math.ceil(INSIDE) + SKIRT + 2;
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int chx = (cx - half) >> 4; chx <= (cx + half) >> 4; chx++) {
            for (int chz = (cz - half) >> 4; chz <= (cz + half) >> 4; chz++) {
                loads.add(world.getChunkAtAsync(chx, chz));
            }
        }
        final SchematicData schem = s;
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((v, ex) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ex != null) {
                        busy = false;
                        admin.sendMessage(plain("&cNao consegui carregar a area: " + ex.getMessage()));
                        return;
                    }
                    try {
                        plan(admin, world, schem, cx, cz, gy);
                    } catch (RuntimeException e) {
                        busy = false;
                        plugin.getLogger().warning("Erro ao montar o lobby: " + e);
                        admin.sendMessage(plain("&cErro ao montar o lobby, veja o console."));
                    }
                }));
        return null;
    }

    private static boolean isTree(Material m) {
        String n = m.name();
        return n.endsWith("LEAVES") || n.endsWith("_LOG") || n.endsWith("_WOOD");
    }

    private void plan(Player admin, World world, SchematicData s, int cx, int cz, int gy) {
        BlockData air = Bukkit.createBlockData(Material.AIR);
        BlockData dirt = Bukkit.createBlockData(Material.DIRT);
        BlockData grass = Bukkit.createBlockData(Material.GRASS_BLOCK);
        BlockData[] pal = new BlockData[s.palette.length];
        for (int i = 0; i < pal.length; i++) {
            // cabecas de jogador (com textura) viram caveiras de esqueleto, que nao dependem de dados extras
            String state = s.palette[i].replace("minecraft:player_head", "minecraft:skeleton_skull");
            try {
                pal[i] = Bukkit.createBlockData(state);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Bloco desconhecido no lobby, trocando por ar: " + state);
                pal[i] = air;
            }
        }

        double midX = (s.width - 1) / 2.0;
        double midZ = (s.length - 1) / 2.0;
        int ox = cx - (int) midX;
        int oz = cz - (int) midZ;
        int minY = world.getMinHeight();
        int clearFrom = gy + s.height - 1;
        List<Op> ops = new ArrayList<>(110_000);

        for (int sz = 0; sz < s.length; sz++) {
            for (int sx = 0; sx < s.width; sx++) {
                double dist = Math.hypot(sx - midX, sz - midZ);
                int x = ox + sx;
                int z = oz + sz;
                if (dist <= INSIDE) {
                    for (int sy = 1; sy < s.height; sy++) {
                        BlockData d = pal[s.ids[(sy * s.length + sz) * s.width + sx]];
                        if (sy == 1 && d.getMaterial().isAir()) {
                            d = grass; // nunca deixa buraco na camada do chao
                        }
                        ops.add(new Op(x, gy + sy - 1, z, d));
                    }
                    int topAll = world.getHighestBlockYAt(x, z);
                    for (int y = clearFrom; y <= topAll; y++) {
                        ops.add(new Op(x, y, z, null));
                    }
                    for (int y = gy - 1; y >= gy - 40 && y > minY; y--) {
                        Block b = world.getBlockAt(x, y, z);
                        if (b.getType().isAir() || b.isLiquid()) {
                            ops.add(new Op(x, y, z, dirt));
                        } else {
                            break;
                        }
                    }
                } else if (dist <= INSIDE + SKIRT) {
                    double t = (dist - INSIDE) / SKIRT;
                    int e = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    while (e > minY && isTree(world.getBlockAt(x, e, z).getType())) {
                        e--;
                    }
                    if (world.getBlockAt(x, e, z).isLiquid()) {
                        continue;
                    }
                    int target = gy + (int) Math.round((e - gy) * t);
                    for (int y = e + 1; y < target; y++) {
                        ops.add(new Op(x, y, z, dirt));
                    }
                    if (target != e) {
                        ops.add(new Op(x, target, z, grass));
                    }
                    int topAll = world.getHighestBlockYAt(x, z);
                    for (int y = target + 1; y <= topAll; y++) {
                        ops.add(new Op(x, y, z, null));
                    }
                }
            }
        }

        Location spawn = findSpawn(s, ox, oz, gy, midX, midZ);
        final int total = ops.size();
        admin.sendMessage(plain("&eColando o lobby (" + total + " blocos). Fique por perto, leva alguns segundos..."));
        apply(admin, world, ops, 0, () -> {
            double radius = INSIDE + SKIRT + Math.max(0, cfg.getDouble("extra-radius", 0));
            zone = new Zone(world.getName(), cx, cz, radius);
            saveZone();
            world.setSpawnLocation(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
            busy = false;
            admin.sendMessage(plain("&aLobby colado! Centro: &fX " + cx + "  Y " + gy + "  Z " + cz
                    + "&a. Spawn do mundo definido em &fX " + spawn.getBlockX() + " Y " + spawn.getBlockY()
                    + " Z " + spawn.getBlockZ() + "&a. Protecao ativada (raio " + (int) radius + ")."));
        });
    }

    private void apply(Player admin, World world, List<Op> ops, int from, Runnable done) {
        int end = Math.min(ops.size(), from + OPS_PER_TICK);
        for (int i = from; i < end; i++) {
            Op op = ops.get(i);
            Block b = world.getBlockAt(op.x(), op.y(), op.z());
            if (op.data() == null) {
                if (!b.getType().isAir()) {
                    b.setType(Material.AIR, false);
                }
            } else if (!(op.data().getMaterial().isAir() && b.getType().isAir())) {
                b.setBlockData(op.data(), false);
            }
        }
        if (end >= ops.size()) {
            done.run();
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> apply(admin, world, ops, end, done), 1L);
        }
    }

    /** Um lugar livre na praca (chao + 2 blocos de ar em cima), perto do centro mas fora da lampada. */
    private Location findSpawn(SchematicData s, int ox, int oz, int gy, double midX, double midZ) {
        double best = Double.MAX_VALUE;
        int bx = (int) midX;
        int bz = (int) midZ;
        for (int sz = 0; sz < s.length; sz++) {
            for (int sx = 0; sx < s.width; sx++) {
                double d = Math.hypot(sx - midX, sz - midZ);
                if (d < 4 || d > 25 || d >= best) {
                    continue;
                }
                String floor = s.stateAt(sx, 1, sz);
                if (floor.startsWith("minecraft:air") || floor.contains("leaves") || floor.contains("grass_block")
                        || !s.stateAt(sx, 2, sz).startsWith("minecraft:air")
                        || !s.stateAt(sx, 3, sz).startsWith("minecraft:air")) {
                    continue;
                }
                best = d;
                bx = sx;
                bz = sz;
            }
        }
        return new Location(null, ox + bx, gy + 1, oz + bz);
    }

    // ------------------------------------------------------------ zona

    private boolean inZone(Location l) {
        Zone z = zone;
        if (z == null || l == null || l.getWorld() == null || !l.getWorld().getName().equals(z.world)) {
            return false;
        }
        double dx = l.getX() - (z.x + 0.5);
        double dz = l.getZ() - (z.z + 0.5);
        return dx * dx + dz * dz <= z.radius * z.radius;
    }

    private boolean isAdmin(Player p) {
        return p.hasPermission("killmoney.admin");
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!cfg.getBoolean("pvp-off", true) || zone == null) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = null;
        Entity damager = event.getDamager();
        if (damager instanceof Player p) {
            attacker = p;
        } else if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        if (inZone(victim.getLocation()) || inZone(attacker.getLocation())) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            Long last = warned.get(attacker.getUniqueId());
            if (last == null || now - last > 3000) {
                warned.put(attacker.getUniqueId(), now);
                attacker.sendMessage(plain(msg("pvp-off")));
            }
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (cfg.getBoolean("protect-blocks", true) && !isAdmin(event.getPlayer())
                && inZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (cfg.getBoolean("protect-blocks", true) && !isAdmin(event.getPlayer())
                && inZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (cfg.getBoolean("protect-blocks", true) && !isAdmin(event.getPlayer())
                && inZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        if (cfg.getBoolean("block-hostile-spawns", true) && event.getEntity() instanceof Monster
                && event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL
                && inZone(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (zone != null) {
            event.blockList().removeIf(b -> inZone(b.getLocation()));
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        if (zone != null) {
            event.blockList().removeIf(b -> inZone(b.getLocation()));
        }
    }

    // ---------------------------------------------------------- comandos

    boolean onCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("killmoney.admin")) {
            sender.sendMessage("Sem permissao.");
            return true;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();
        switch (sub) {
            case "paste": {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Use dentro do jogo, em pe no centro de onde quer o lobby.");
                    return true;
                }
                String variant = args.length > 1 ? args[1].toLowerCase() : "rustic";
                String err = paste(player, variant);
                if (err != null) {
                    sender.sendMessage(err);
                }
                return true;
            }
            case "remove":
                zone = null;
                saveZone();
                sender.sendMessage("Protecao do lobby removida (a construcao continua no mapa).");
                return true;
            case "reload":
                reload();
                sender.sendMessage("lobby.yml recarregado.");
                return true;
            default: {
                Zone z = zone;
                if (z == null) {
                    sender.sendMessage("Nenhum lobby colado. Fique em pe no chao e use /lobby paste [rustic|stony].");
                } else {
                    sender.sendMessage("Lobby em " + z.world + " X " + z.x + " Z " + z.z + " (raio de protecao "
                            + (int) z.radius + "). Comandos: /lobby paste [rustic|stony], /lobby remove, /lobby reload");
                }
                return true;
            }
        }
    }
}
