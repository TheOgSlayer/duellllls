package duels;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class DuelPlugin extends JavaPlugin implements Listener, CommandExecutor {
    static final int SIZE = 200, GAP = 300, Y = 64, WALL_H = 10, FENCE_H = 6;
    static final int START_ARENAS = 2, MAX_ARENAS = 8;

    static class Arena {
        final int id, x0; boolean ready, busy;
        final Map<Location, BlockData> changes = new HashMap<>();
        Arena(int id) { this.id = id; this.x0 = id * GAP; }
    }
    static class Duel {
        final Player a, b; final Arena arena; boolean live, ended;
        final Set<UUID> specs = new HashSet<>();
        Duel(Player a, Player b, Arena ar) { this.a = a; this.b = b; this.arena = ar; }
        Player other(Player p) { return p.equals(a) ? b : a; }
    }
    record Saved(Location loc, GameMode gm) {}

    World world;
    final Random rnd = new Random();
    final List<Arena> arenas = new ArrayList<>();
    final Map<UUID, Duel> duels = new HashMap<>();
    final Map<UUID, Duel> specOf = new HashMap<>();
    final Map<UUID, UUID> requests = new HashMap<>();   // target -> challenger
    final Map<UUID, Saved> saved = new HashMap<>();
    final Deque<Player[]> queue = new ArrayDeque<>();

    @Override public void onEnable() {
        world = new WorldCreator("duel_arenas").generator(new ChunkGenerator() {}).createWorld();
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setTime(6000);
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("duel").setExecutor(this);
        for (int i = 0; i < START_ARENAS; i++) newArena();
    }

    // ---------- arena building ----------
    void newArena() { Arena a = new Arena(arenas.size()); arenas.add(a); build(a); }

    void build(Arena a) {
        new BukkitRunnable() {
            int r = -1;
            public void run() {
                for (int n = 0; n < 3 && r <= SIZE; n++, r++) {
                    for (int x = -1; x <= SIZE; x++) {
                        boolean eX = x < 0 || x >= SIZE, eZ = r < 0 || r >= SIZE;
                        int bx = a.x0 + x;
                        if (eX || eZ) wall(bx, r, (eX && eZ) || ((eZ ? x + 1 : r + 1) % 20 == 0));
                        else {
                            set(bx, Y - 2, r, Material.BEDROCK);
                            set(bx, Y - 1, r, Material.STONE);
                            set(bx, Y, r, Material.SMOOTH_STONE);
                        }
                    }
                }
                if (r > SIZE) { a.ready = true; cancel(); getLogger().info("Arena " + (a.id + 1) + " ready"); processQueue(); }
            }
        }.runTaskTimer(this, 1, 1);
    }

    void wall(int x, int z, boolean pillar) {
        for (int h = -2; h <= WALL_H; h++) {
            Material m;
            if (pillar) m = Material.POLISHED_BLACKSTONE_BRICKS;
            else if (h == WALL_H) m = Material.POLISHED_BLACKSTONE;
            else if (h == 0) m = Material.CHISELED_STONE_BRICKS;
            else m = rnd.nextInt(8) == 0 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
            set(x, Y + h, z, m);
        }
        if (pillar) { set(x, Y + WALL_H + 1, z, Material.POLISHED_BLACKSTONE); set(x, Y + WALL_H + 2, z, Material.LANTERN); }
    }

    void set(int x, int y, int z, Material m) { world.getBlockAt(x, y, z).setType(m, false); }

    Arena free() { for (Arena a : arenas) if (a.ready && !a.busy) return a; return null; }

    Arena arenaAt(Location l) {
        if (l.getWorld() != world) return null;
        for (Arena a : arenas)
            if (l.getBlockX() >= a.x0 - 1 && l.getBlockX() <= a.x0 + SIZE && l.getBlockZ() >= -1 && l.getBlockZ() <= SIZE) return a;
        return null;
    }

    // ---------- duel flow ----------
    void processQueue() {
        while (!queue.isEmpty()) {
            Arena a = free();
            if (a == null) {
                if (arenas.size() < MAX_ARENAS && arenas.stream().allMatch(x -> x.ready)) newArena();
                return;
            }
            Player[] p = queue.poll();
            if (!p[0].isOnline() || !p[1].isOnline()) continue;
            begin(a, p[0], p[1]);
        }
    }

    void begin(Arena ar, Player p1, Player p2) {
        ar.busy = true;
        Duel d = new Duel(p1, p2, ar);
        duels.put(p1.getUniqueId(), d); duels.put(p2.getUniqueId(), d);
        int cx = ar.x0 + SIZE / 2;
        for (Player p : new Player[]{p1, p2}) {
            saved.put(p.getUniqueId(), new Saved(p.getLocation(), p.getGameMode()));
            p.setGameMode(GameMode.SURVIVAL); p.setHealth(20); p.setFoodLevel(20); p.setFireTicks(0);
            p.sendMessage("§6Duel starting in arena " + (ar.id + 1) + "!");
        }
        p1.teleport(new Location(world, cx + .5, Y + 1, 30.5, 0, 0));
        p2.teleport(new Location(world, cx + .5, Y + 1, SIZE - 29.5, 180, 0));
        fence(d);
    }

    void layer(Arena a, int l, boolean up) {
        for (int x = 0; x < SIZE; x++) set(a.x0 + x, Y + 1 + l, SIZE / 2, up ? Material.NETHER_BRICK_FENCE : Material.AIR);
    }

    // fence rises one layer every 0.5s, fight starts after 5s, fence then lowers smoothly
    void fence(Duel d) {
        new BukkitRunnable() {
            int t = 0;
            public void run() {
                if (d.ended) { for (int l = 0; l < FENCE_H; l++) layer(d.arena, l, false); cancel(); return; }
                if (t < FENCE_H * 10 && t % 10 == 0) layer(d.arena, t / 10, true);
                if (t < 100 && t % 20 == 0) for (Player p : new Player[]{d.a, d.b}) p.sendTitle("§e" + (5 - t / 20), "", 0, 20, 0);
                if (t == 100) { d.live = true; for (Player p : new Player[]{d.a, d.b}) p.sendTitle("§aFIGHT!", "", 0, 20, 10); }
                if (t >= 100 && (t - 100) % 5 == 0) {
                    int l = FENCE_H - 1 - (t - 100) / 5;
                    if (l >= 0) layer(d.arena, l, false); else cancel();
                }
                t++;
            }
        }.runTaskTimer(this, 0, 1);
    }

    void end(Duel d, Player winner) {
        if (d.ended) return;
        d.ended = true;
        duels.remove(d.a.getUniqueId()); duels.remove(d.b.getUniqueId());
        String msg = "§6" + winner.getName() + " won the duel against " + d.other(winner).getName() + "!";
        for (UUID u : d.specs) { Player s = Bukkit.getPlayer(u); specOf.remove(u); if (s != null) { s.sendMessage(msg); restore(s); } }
        for (Player p : new Player[]{d.a, d.b}) { p.sendMessage(msg); restore(p); }
        for (int l = 0; l < FENCE_H; l++) layer(d.arena, l, false);
        d.arena.changes.forEach((loc, bd) -> loc.getBlock().setBlockData(bd, false));
        d.arena.changes.clear();
        d.arena.busy = false;
        processQueue();
    }

    void restore(Player p) {
        Saved s = saved.remove(p.getUniqueId());
        p.setHealth(20); p.setFireTicks(0);
        if (s != null) { p.setGameMode(s.gm()); p.teleport(s.loc()); }
    }

    // ---------- commands ----------
    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (args.length == 0) { p.sendMessage("§cUsage: /duel <player> | accept | spec <player> | leave"); return true; }
        UUID id = p.getUniqueId();
        switch (args[0].toLowerCase()) {
            case "accept" -> {
                UUID c = requests.remove(id);
                Player ch = c == null ? null : Bukkit.getPlayer(c);
                if (ch == null) { p.sendMessage("§cNo pending challenge."); return true; }
                if (busy(ch) || busy(p)) { p.sendMessage("§cOne of you is already busy."); return true; }
                queue.add(new Player[]{ch, p});
                if (free() == null) { ch.sendMessage("§eAll arenas busy, you are queued."); p.sendMessage("§eAll arenas busy, you are queued."); }
                processQueue();
            }
            case "leave" -> {
                Duel d = specOf.remove(id);
                if (d == null) { p.sendMessage("§cYou are not spectating."); return true; }
                d.specs.remove(id); restore(p);
            }
            case "spec" -> {
                if (args.length < 2) { p.sendMessage("§cUsage: /duel spec <player>"); return true; }
                Player t = Bukkit.getPlayerExact(args[1]);
                Duel d = t == null ? null : duels.get(t.getUniqueId());
                if (d == null) { p.sendMessage("§cThat player is not in a duel."); return true; }
                if (busy(p)) { p.sendMessage("§cUse /duel leave first."); return true; }
                saved.put(id, new Saved(p.getLocation(), p.getGameMode()));
                p.setGameMode(GameMode.SPECTATOR);
                p.teleport(new Location(world, d.arena.x0 + SIZE / 2 + .5, Y + 12, SIZE / 2 + .5));
                d.specs.add(id); specOf.put(id, d);
                p.sendMessage("§aSpectating. Use /duel leave to go back.");
            }
            default -> {
                Player t = Bukkit.getPlayerExact(args[0]);
                if (t == null || t == p) { p.sendMessage("§cPlayer not found."); return true; }
                if (busy(p) || busy(t)) { p.sendMessage("§cOne of you is already busy."); return true; }
                requests.put(t.getUniqueId(), id);
                p.sendMessage("§aChallenge sent to " + t.getName());
                t.sendMessage("§6" + p.getName() + " challenged you! Type §e/duel accept");
            }
        }
        return true;
    }

    boolean busy(Player p) { return duels.containsKey(p.getUniqueId()) || specOf.containsKey(p.getUniqueId()); }

    // ---------- events ----------
    @EventHandler public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Duel d = duels.get(p.getUniqueId());
        if (d == null) return;
        if (!d.live) { e.setCancelled(true); return; }
        if (p.getHealth() - e.getFinalDamage() <= 0) { e.setCancelled(true); end(d, d.other(p)); }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Duel d = duels.get(p.getUniqueId());
        if (d != null) { end(d, d.other(p)); return; }
        Duel s = specOf.remove(p.getUniqueId());
        if (s != null) { s.specs.remove(p.getUniqueId()); restore(p); }
        queue.removeIf(x -> x[0].equals(p) || x[1].equals(p));
    }

    boolean canEdit(Arena a, Location l) {
        return l.getBlockX() >= a.x0 && l.getBlockX() < a.x0 + SIZE && l.getBlockZ() >= 0 && l.getBlockZ() < SIZE
                && l.getBlockY() >= Y - 1 && l.getBlockY() < Y + WALL_H;
    }

    @EventHandler public void onBreak(BlockBreakEvent e) {
        Location l = e.getBlock().getLocation();
        Arena a = arenaAt(l);
        if (a == null) return;
        Material m = e.getBlock().getType();
        if (!canEdit(a, l) || m == Material.BEDROCK || m == Material.NETHER_BRICK_FENCE || !duels.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true); return;
        }
        a.changes.putIfAbsent(l, e.getBlock().getBlockData());
    }

    @EventHandler public void onPlace(BlockPlaceEvent e) {
        Location l = e.getBlock().getLocation();
        Arena a = arenaAt(l);
        if (a == null) return;
        if (!canEdit(a, l) || !duels.containsKey(e.getPlayer().getUniqueId())) { e.setCancelled(true); return; }
        a.changes.putIfAbsent(l, e.getBlockReplacedState().getBlockData());
    }
}
