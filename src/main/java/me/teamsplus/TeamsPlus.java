package me.teamsplus;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

public class TeamsPlus extends JavaPlugin implements Listener {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,12}$");
    private static final String SB_PREFIX = "tp_";
    private static final long INVITE_MS = 60_000L;

    private final Map<String, Team> teams = new LinkedHashMap<>();      // lowercase name -> team
    private final Map<UUID, String> playerTeam = new HashMap<>();       // player -> lowercase team name
    private final Map<UUID, Invite> invites = new HashMap<>();          // invitee -> invite
    private final Set<UUID> chatOn = new HashSet<>();
    private final Map<UUID, BukkitRunnable> teleports = new HashMap<>();

    private record Invite(String team, long expires) {}

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        loadTeams();
        Bukkit.getPluginManager().registerEvents(this, this);
        cleanScoreboardTeams();
        for (Team t : teams.values()) refreshScoreboard(t);
        for (Player p : Bukkit.getOnlinePlayers()) syncGlow(p);
    }

    @Override
    public void onDisable() {
        saveTeams();
        for (Player p : Bukkit.getOnlinePlayers()) p.setGlowing(false);
        cleanScoreboardTeams();
    }

    // ---------------------------------------------------------------- storage

    private File dataFile() { return new File(getDataFolder(), "teams.yml"); }

    private void loadTeams() {
        teams.clear();
        playerTeam.clear();
        if (!dataFile().exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile());
        ConfigurationSection root = yml.getConfigurationSection("teams");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Team t = new Team(s.getString("name", key), UUID.fromString(s.getString("owner")));
                t.members.clear();
                for (String m : s.getStringList("members")) t.members.add(UUID.fromString(m));
                t.members.add(t.owner);
                NamedTextColor c = NamedTextColor.NAMES.value(s.getString("color", "yellow"));
                t.color = c != null ? c : NamedTextColor.YELLOW;
                t.pvp = s.getBoolean("pvp", false);
                if (s.isConfigurationSection("home")) {
                    ConfigurationSection h = s.getConfigurationSection("home");
                    World w = Bukkit.getWorld(h.getString("world", ""));
                    if (w != null) {
                        t.home = new Location(w, h.getDouble("x"), h.getDouble("y"), h.getDouble("z"),
                                (float) h.getDouble("yaw"), (float) h.getDouble("pitch"));
                    }
                }
                teams.put(t.name.toLowerCase(), t);
                for (UUID u : t.members) playerTeam.put(u, t.name.toLowerCase());
            } catch (Exception ex) {
                getLogger().warning("Could not load team '" + key + "': " + ex.getMessage());
            }
        }
    }

    private void saveTeams() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Team t : teams.values()) {
            String p = "teams." + t.name.toLowerCase();
            yml.set(p + ".name", t.name);
            yml.set(p + ".owner", t.owner.toString());
            yml.set(p + ".color", t.color.toString());
            yml.set(p + ".pvp", t.pvp);
            List<String> ms = new ArrayList<>();
            for (UUID u : t.members) ms.add(u.toString());
            yml.set(p + ".members", ms);
            if (t.home != null && t.home.getWorld() != null) {
                yml.set(p + ".home.world", t.home.getWorld().getName());
                yml.set(p + ".home.x", t.home.getX());
                yml.set(p + ".home.y", t.home.getY());
                yml.set(p + ".home.z", t.home.getZ());
                yml.set(p + ".home.yaw", t.home.getYaw());
                yml.set(p + ".home.pitch", t.home.getPitch());
            }
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile());
        } catch (IOException e) {
            getLogger().severe("Could not save teams.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private Team teamOf(UUID u) {
        String k = playerTeam.get(u);
        return k == null ? null : teams.get(k);
    }

    private boolean isOwner(Team t, Player p) { return t.owner.equals(p.getUniqueId()); }

    private void msg(CommandSender to, String text, NamedTextColor c) {
        to.sendMessage(Component.text(text, c));
    }

    private void err(CommandSender to, String text) { msg(to, text, NamedTextColor.RED); }

    private void ok(CommandSender to, String text) { msg(to, text, NamedTextColor.GREEN); }

    private void broadcastTeam(Team t, Component c) {
        for (UUID u : t.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(c);
        }
    }

    private String nameOf(UUID u) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName() != null ? op.getName() : u.toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- scoreboard / glow

    private void cleanScoreboardTeams() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (org.bukkit.scoreboard.Team st : new ArrayList<>(sb.getTeams())) {
            if (st.getName().startsWith(SB_PREFIX)) st.unregister();
        }
    }

    private void refreshScoreboard(Team t) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String id = SB_PREFIX + t.name.toLowerCase();
        org.bukkit.scoreboard.Team st = sb.getTeam(id);
        if (st == null) st = sb.registerNewTeam(id);
        st.color(t.color);
        st.prefix(Component.empty());
        st.setAllowFriendlyFire(true); // damage handled by our own pvp toggle
        for (String e : new HashSet<>(st.getEntries())) st.removeEntry(e);
        for (UUID u : t.members) st.addEntry(nameOf(u));
    }

    private void removeScoreboard(Team t) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team st = sb.getTeam(SB_PREFIX + t.name.toLowerCase());
        if (st != null) st.unregister();
    }

    private void leaveScoreboard(Team t, UUID u) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team st = sb.getTeam(SB_PREFIX + t.name.toLowerCase());
        if (st != null) st.removeEntry(nameOf(u));
    }

    private void syncGlow(Player p) {
        Team t = teamOf(p.getUniqueId());
        if (t != null) {
            refreshScoreboard(t);
            p.setGlowing(true);
        } else {
            p.setGlowing(false);
        }
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) {
            err(sender, "Only players can use /team.");
            return true;
        }
        if (a.length == 0) { help(p); return true; }

        switch (a[0].toLowerCase()) {
            case "create" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("home")) setHome(p);
                else create(p, a);
            }
            case "disband" -> disband(p, a);
            case "invite" -> invite(p, a);
            case "accept" -> accept(p);
            case "leave" -> leave(p);
            case "kick" -> kick(p, a);
            case "pvp" -> pvp(p, a);
            case "chat" -> chat(p, a);
            case "color", "colour" -> color(p, a);
            case "del", "delete" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("home")) delHome(p);
                else err(p, "Usage: /team del home");
            }
            case "sethome" -> setHome(p);
            case "delhome" -> delHome(p);
            case "home" -> home(p);
            case "info" -> info(p);
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        msg(p, "---- Team commands ----", NamedTextColor.GOLD);
        String[] lines = {
                "/team create <name> [color]",
                "/team disband <name>  (owner)",
                "/team invite <player>",
                "/team accept  |  /team leave",
                "/team kick <player>  (owner)",
                "/team pvp enable|disable  (owner)",
                "/team chat [enable|disable]",
                "/team color <color>  (owner)",
                "/team create home  |  /team del home  (owner)",
                "/team home",
                "/team info"
        };
        for (String l : lines) msg(p, l, NamedTextColor.YELLOW);
    }

    private void create(Player p, String[] a) {
        if (a.length < 2) { err(p, "Usage: /team create <name> [color]"); return; }
        if (teamOf(p.getUniqueId()) != null) { err(p, "You are already in a team. Leave it first."); return; }
        String name = a[1];
        if (!NAME_PATTERN.matcher(name).matches()) {
            err(p, "Team name must be 3-12 characters: letters, numbers, underscore.");
            return;
        }
        if (teams.containsKey(name.toLowerCase())) { err(p, "That team name is taken."); return; }

        Team t = new Team(name, p.getUniqueId());
        if (a.length >= 3) {
            NamedTextColor c = NamedTextColor.NAMES.value(a[2].toLowerCase());
            if (c == null) { err(p, "Unknown color. Try: red, blue, green, aqua, gold, light_purple, ..."); return; }
            t.color = c;
        }
        teams.put(name.toLowerCase(), t);
        playerTeam.put(p.getUniqueId(), name.toLowerCase());
        refreshScoreboard(t);
        syncGlow(p);
        saveTeams();
        p.sendMessage(Component.text("Team ", NamedTextColor.GREEN)
                .append(Component.text(t.name, t.color))
                .append(Component.text(" created. You are the owner.", NamedTextColor.GREEN)));
    }

    private void disband(Player p, String[] a) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { err(p, "You are not in a team."); return; }
        if (!isOwner(t, p)) { err(p, "Only the team owner can disband the team."); return; }
        if (a.length >= 2 && !a[1].equalsIgnoreCase(t.name)) {
            err(p, "That is not your team's name. Your team is " + t.name + ".");
            return;
        }
        broadcastTeam(t, Component.text("Team " + t.name + " was disbanded by the owner.", NamedTextColor.RED));
        removeScoreboard(t);
        for (UUID u : new ArrayList<>(t.members)) {
            playerTeam.remove(u);
            chatOn.remove(u);
            cancelTeleport(u);
            Player m = Bukkit.getPlayer(u);
            if (m != null) m.setGlowing(false);
        }
        teams.remove(t.name.toLowerCase());
        saveTeams();
    }

    private void invite(Player p, String[] a) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { err(p, "You are not in a team."); return; }
        if (a.length < 2) { err(p, "Usage: /team invite <player>"); return; }
        Player target = Bukkit.getPlayerExact(a[1]);
        if (target == null) { err(p, "That player is not online."); return; }
        if (target.equals(p)) { err(p, "You can't invite yourself."); return; }
        if (teamOf(target.getUniqueId()) != null) { err(p, target.getName() + " is already in a team."); return; }

        invites.put(target.getUniqueId(), new Invite(t.name.toLowerCase(), System.currentTimeMillis() + INVITE_MS));
        ok(p, "Invited " + target.getName() + " to your team.");
        target.sendMessage(Component.text(p.getName() + " invited you to team ", NamedTextColor.GREEN)
                .append(Component.text(t.name, t.color))
                .append(Component.text(". Type /team accept (expires in 60s).", NamedTextColor.GREEN)));
    }

    private void accept(Player p) {
        Invite inv = invites.remove(p.getUniqueId());
        if (inv == null || inv.expires() < System.currentTimeMillis()) {
            err(p, "You have no pending invite.");
            return;
        }
        if (teamOf(p.getUniqueId()) != null) { err(p, "You are already in a team."); return; }
        Team t = teams.get(inv.team());
        if (t == null) { err(p, "That team no longer exists."); return; }

        t.members.add(p.getUniqueId());
        playerTeam.put(p.getUniqueId(), t.name.toLowerCase());
        refreshScoreboard(t);
        syncGlow(p);
        saveTeams();
        broadcastTeam(t, Component.text(p.getName() + " joined the team!", NamedTextColor.GREEN));
    }

    private void leave(Player p) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { err(p, "You are not in a team."); return; }
        if (isOwner(t, p)) { err(p, "You own this team. Use /team disband " + t.name + " instead."); return; }
        t.members.remove(p.getUniqueId());
        playerTeam.remove(p.getUniqueId());
        chatOn.remove(p.getUniqueId());
        cancelTeleport(p.getUniqueId());
        leaveScoreboard(t, p.getUniqueId());
        p.setGlowing(false);
        saveTeams();
        ok(p, "You left " + t.name + ".");
        broadcastTeam(t, Component.text(p.getName() + " left the team.", NamedTextColor.YELLOW));
    }

    @SuppressWarnings("deprecation")
    private void kick(Player p, String[] a) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { err(p, "You are not in a team."); return; }
        if (!isOwner(t, p)) { err(p, "Only the team owner can kick members."); return; }
        if (a.length < 2) { err(p, "Usage: /team kick <player>"); return; }

        UUID target = null;
        for (UUID u : t.members) {
            if (nameOf(u).equalsIgnoreCase(a[1])) { target = u; break; }
        }
        if (target == null) { err(p, a[1] + " is not in your team."); return; }
        if (target.equals(p.getUniqueId())) { err(p, "You can't kick yourself. Use /team disband."); return; }

        leaveScoreboard(t, target);
        t.members.remove(target);
        playerTeam.remove(target);
        chatOn.remove(target);
        cancelTeleport(target);
        Player tp = Bukkit.getPlayer(target);
        if (tp != null) {
            tp.setGlowing(false);
            err(tp, "You were kicked from team " + t.name + ".");
        }
        saveTeams();
        ok(p, "Kicked " + a[1] + ".");
    }

    private void pvp(Player p, String[] a) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { err(p, "You are not in a team."); return; }
        if (!isOwner(t, p)) { err(p, "Only the team owner can change team PvP."); return; }
        if (a.length < 2 || !(a[1].equalsIgnoreCase("enable") || a[1].equalsIgnoreCase("disable"))) {
            err(p, "Usage: /team pvp enable|disable");
            return;
        }
        t.pvp = a[1].equalsIgnoreCase("enable");
        saveTeams();
        broadcastTeam(t, Component.text("Team PvP is now " + (t.pvp ? "ENABLED" : "DISABLED") + ".",
                t.pvp ? NamedTextColor.RED : NamedTextColor.GREEN));
    }

    private void chat(Player p, String[] a) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { err(p, "You are not in a team."); return; }
        boolean on;
        if (a.length >= 2 && a[1].equalsIgnoreCase("enable")) on = true;
        else if (a.length >= 2 && a[1].equalsIgnoreCase("disable")) on = false;
        else on = !chatOn.contains(p.getUniqueId());
        if (on) chatOn.add(p.getUniqueId()); else chatOn.remove(p.getUniqueId());
        ok(p, on ? "Team chat ON. Your messages now go only to your team." : "Team chat OFF. Back to public chat.");
    }

    private void color(Player p, String[] a) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { err(p, "You are not in a team."); return; }
        if (!isOwner(t, p)) { err(p, "Only the team owner can change the team color."); return; }
        if (a.length < 2) { err(p, "Usage: /team color <color>"); return; }
        NamedTextColor c = NamedTextColor.NAMES.value(a[1].toLowerCase());
        if (c == null) { err(p, "Unknown color. Try: red, blue, green, aqua, gold, light_purple, yellow, white..."); return; }
        t.color = c;
        refreshScoreboard(t);
        saveTeams();
        broadcastTeam(t, Component.text("Team color changed to " + a[1].toLowerCase() + ".", c));
    }

    private void setHome(Player p) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { err(p, "You are not in a team."); return; }
        if (!isOwner(t, p)) { err(p, "Only the team owner can set the team home."); return; }
        t.home = p.getLocation().clone();
        saveTeams();
        broadcastTeam(t, Component.text("Team home set by " + p.getName() + ".", NamedTextColor.GREEN));
    }

    private void delHome(Player p) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { err(p, "You are not in a team."); return; }
        if (!isOwner(t, p)) { err(p, "Only the team owner can delete the team home."); return; }
        if (t.home == null) { err(p, "Your team has no home."); return; }
        t.home = null;
        saveTeams();
        broadcastTeam(t, Component.text("Team home deleted. The owner can set a new one with /team create home.",
                NamedTextColor.YELLOW));
    }

    private void home(Player p) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { err(p, "You are not in a team."); return; }
        if (t.home == null) { err(p, "Your team has no home yet."); return; }
        startTeleport(p, t.home.clone());
    }

    private void info(Player p) {
        Team t = teamOf(p.getUniqueId());
        if (t == null) { err(p, "You are not in a team."); return; }
        p.sendMessage(Component.text("---- Team ", NamedTextColor.GOLD)
                .append(Component.text(t.name, t.color))
                .append(Component.text(" ----", NamedTextColor.GOLD)));
        p.sendMessage(Component.text("Owner: ", NamedTextColor.GRAY)
                .append(Component.text("\u265B " + nameOf(t.owner), NamedTextColor.GOLD)));
        StringBuilder sb = new StringBuilder();
        for (UUID u : t.members) {
            if (u.equals(t.owner)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(nameOf(u));
        }
        msg(p, "Members: " + (sb.length() == 0 ? "(just the owner)" : sb), NamedTextColor.GRAY);
        msg(p, "PvP: " + (t.pvp ? "enabled" : "disabled") + "   Home: " + (t.home != null ? "set" : "not set"),
                NamedTextColor.GRAY);
    }

    // ---------------------------------------------------------------- teleport (5s, cancels on move)

    private void cancelTeleport(UUID u) {
        BukkitRunnable r = teleports.remove(u);
        if (r != null) r.cancel();
    }

    private void startTeleport(Player p, Location dest) {
        cancelTeleport(p.getUniqueId());
        final Location start = p.getLocation().clone();
        BukkitRunnable task = new BukkitRunnable() {
            int ticks = 0;
            int lastShown = -1;

            @Override
            public void run() {
                if (!p.isOnline()) { teleports.remove(p.getUniqueId()); cancel(); return; }
                Location now = p.getLocation();
                if (now.getBlockX() != start.getBlockX() || now.getBlockY() != start.getBlockY()
                        || now.getBlockZ() != start.getBlockZ() || now.getWorld() != start.getWorld()) {
                    p.sendActionBar(Component.text("Teleport cancelled - you moved!", NamedTextColor.RED));
                    teleports.remove(p.getUniqueId());
                    cancel();
                    return;
                }
                if (ticks >= 100) {
                    teleports.remove(p.getUniqueId());
                    cancel();
                    p.teleportAsync(dest);
                    p.sendActionBar(Component.text("Teleported to team home!", NamedTextColor.GREEN));
                    return;
                }
                int left = 5 - ticks / 20;
                if (left != lastShown) {
                    lastShown = left;
                    p.sendActionBar(Component.text("Teleporting to team home in " + left + "s... don't move!",
                            NamedTextColor.YELLOW));
                }
                ticks += 5;
            }
        };
        teleports.put(p.getUniqueId(), task);
        task.runTaskTimer(this, 0L, 5L);
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        syncGlow(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancelTeleport(e.getPlayer().getUniqueId());
        invites.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player pl) attacker = pl;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl) attacker = pl;
        if (attacker == null || attacker.equals(victim)) return;

        Team at = teamOf(attacker.getUniqueId());
        if (at != null && at == teamOf(victim.getUniqueId()) && !at.pvp) {
            e.setCancelled(true);
            attacker.sendActionBar(Component.text("Team PvP is disabled.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Team t = teamOf(p.getUniqueId());

        Component crown = Component.empty();
        if (t != null && t.owner.equals(p.getUniqueId())) {
            crown = Component.text("\u265B ", NamedTextColor.GOLD);
        }
        final Component crownF = crown;

        if (t != null && chatOn.contains(p.getUniqueId())) {
            // private team chat: only teammates (and console) see it, in the team color (yellow by default)
            e.viewers().removeIf((Audience v) ->
                    !(v instanceof ConsoleCommandSender)
                            && !(v instanceof Player pl && t.members.contains(pl.getUniqueId())));
            e.renderer((source, displayName, message, viewer) ->
                    Component.text("[Team] ", t.color, TextDecoration.BOLD)
                            .append(crownF)
                            .append(Component.text(source.getName(), t.color))
                            .append(Component.text(": ", NamedTextColor.GRAY))
                            .append(message.color(t.color)));
        } else {
            e.renderer((source, displayName, message, viewer) -> {
                Component out = Component.empty().append(crownF);
                if (t != null) {
                    out = out.append(Component.text("[" + t.name + "] ", t.color));
                }
                return out.append(Component.text(source.getName(), t != null ? t.color : NamedTextColor.WHITE))
                        .append(Component.text(": ", NamedTextColor.GRAY))
                        .append(message);
            });
        }
    }

    // ---------------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("create", "disband", "invite", "accept", "leave", "kick", "pvp", "chat",
                    "color", "del", "home", "info"));
        } else if (a.length == 2) {
            switch (a[0].toLowerCase()) {
                case "invite" -> Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
                case "kick" -> {
                    if (sender instanceof Player p && teamOf(p.getUniqueId()) != null) {
                        for (UUID u : teamOf(p.getUniqueId()).members) out.add(nameOf(u));
                    }
                }
                case "pvp" -> out.addAll(List.of("enable", "disable"));
                case "chat" -> out.addAll(List.of("enable", "disable"));
                case "color", "colour" -> out.addAll(NamedTextColor.NAMES.keys());
                case "del", "delete" -> out.add("home");
                case "create" -> out.add("home");
                case "disband" -> {
                    if (sender instanceof Player p && teamOf(p.getUniqueId()) != null) {
                        out.add(teamOf(p.getUniqueId()).name);
                    }
                }
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("create")) {
            out.addAll(NamedTextColor.NAMES.keys());
        }
        String last = a[a.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(last));
        return out;
    }
}
