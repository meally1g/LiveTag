package dev.livetag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LiveTag extends JavaPlugin implements Listener, TabExecutor {

    private static final String TEAM_NAME = "lt_live";
    private static final int BUTTON_SLOT = 13;

    private static final Pattern LOGIN_RULE = Pattern.compile("^[A-Za-z0-9_]{3,25}$");
    private static final Pattern LOGIN_JSON = Pattern.compile("\"user_login\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TOKEN_JSON = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern EXPIRES_JSON = Pattern.compile("\"expires_in\"\\s*:\\s*(\\d+)");

    /** Players who switched the tag on by hand with /live. */
    private final Set<UUID> live = new HashSet<>();
    /** Players who are live on Twitch right now (main thread only). */
    private final Set<UUID> twitchLive = new HashSet<>();
    /** Player -> Twitch login name (lowercase). */
    private final Map<UUID, String> links = new ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private volatile String accessToken;
    private volatile long tokenExpiresAt;
    private BukkitTask pollTask;
    private boolean warnedNoCreds;
    private boolean firstPollDone;

    private File dataFile;

    /** Marker so we can recognise our own menu. */
    private static class MenuHolder implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("live").setExecutor(this);
        getCommand("live").setTabCompleter(this);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (shouldShow(p.getUniqueId())) apply(p, true);
        }

        startPolling();
    }

    @Override
    public void onDisable() {
        if (pollTask != null) pollTask.cancel();
        saveData();
    }

    // ------------------------------------------------------------ data

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : yml.getStringList("live")) {
            try {
                live.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ConfigurationSection sec = yml.getConfigurationSection("links");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String login = sec.getString(key, "");
                if (login.isEmpty()) continue;
                try {
                    links.put(UUID.fromString(key), login.toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("live", live.stream().map(UUID::toString).toList());
        for (Map.Entry<UUID, String> en : links.entrySet()) {
            yml.set("links." + en.getKey(), en.getValue());
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    private String msg(String key) {
        return getConfig().getString("messages." + key, "");
    }

    // ------------------------------------------------------------ core

    private boolean shouldShow(UUID id) {
        return live.contains(id) || twitchLive.contains(id);
    }

    private Component prefix() {
        return MiniMessage.miniMessage().deserialize(
                getConfig().getString("prefix", "<dark_purple><bold>[LIVE]</bold></dark_purple> "));
    }

    /**
     * A scoreboard team prefix shows the tag above the player's head.
     * The display name covers chat, and the player list name covers the tab list.
     */
    private Team team() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam(TEAM_NAME);
        if (t == null) t = sb.registerNewTeam(TEAM_NAME);
        t.prefix(prefix());
        return t;
    }

    /** True if the TAB plugin is installed - it controls the tab list itself. */
    private boolean hasTabPlugin() {
        return Bukkit.getPluginManager().getPlugin("TAB") != null;
    }

    /** Asks the TAB plugin to show (or remove) the tag in the tab list. */
    private void syncTabPlugin(Player p, boolean on) {
        if (!hasTabPlugin()) return;
        String cmd = "tab player " + p.getName() + " tabprefix";
        if (on) {
            cmd += " " + getConfig().getString("tab-prefix", "&5&l[LIVE] &r");
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    private void apply(Player p, boolean on) {
        Team t = team();
        if (on) {
            t.addEntry(p.getName());
            p.displayName(prefix().append(Component.text(p.getName())));
            if (!hasTabPlugin()) {
                p.playerListName(prefix().append(Component.text(p.getName())));
            }
        } else {
            t.removeEntry(p.getName());
            p.displayName(null);
            if (!hasTabPlugin()) {
                p.playerListName(null);
            }
        }
    }

    /** Re-applies the right state for a player (manual toggle OR live on Twitch). */
    private void refresh(Player p) {
        boolean show = shouldShow(p.getUniqueId());
        apply(p, show);
        syncTabPlugin(p, show);
    }

    private void setLive(Player p, boolean on) {
        UUID id = p.getUniqueId();
        if (on) live.add(id);
        else live.remove(id);
        saveData();
        refresh(p);

        if (!on && twitchLive.contains(id)) {
            p.sendMessage(Component.text(
                    "Manual tag off - but you're live on Twitch, so the tag stays until your stream ends.",
                    NamedTextColor.GRAY));
            return;
        }
        p.sendMessage(Component.text(msg(on ? "enabled" : "disabled"),
                on ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------ Twitch

    private void startPolling() {
        if (pollTask != null) pollTask.cancel();
        pollTask = null;
        if (!getConfig().getBoolean("twitch.enabled", true)) return;
        long interval = Math.max(30L, getConfig().getLong("twitch.check-interval-seconds", 60L));
        pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, this::pollTwitch, 100L, interval * 20L);
    }

    /** Runs off the main thread. Asks Twitch which linked streamers are live. */
    private void pollTwitch() {
        String clientId = getConfig().getString("twitch.client-id", "").trim();
        String secret = getConfig().getString("twitch.client-secret", "").trim();
        if (clientId.isEmpty() || secret.isEmpty()) {
            if (!warnedNoCreds) {
                warnedNoCreds = true;
                getLogger().warning("Twitch auto-detect is enabled but twitch.client-id / "
                        + "twitch.client-secret are empty in config.yml.");
            }
            return;
        }
        if (links.isEmpty()) return;

        try {
            String token = getToken(clientId, secret);
            if (token == null) return;

            Set<String> liveLogins = new HashSet<>();
            List<String> logins = new ArrayList<>(new HashSet<>(links.values()));
            for (int i = 0; i < logins.size(); i += 100) {
                List<String> chunk = logins.subList(i, Math.min(i + 100, logins.size()));
                StringBuilder url = new StringBuilder("https://api.twitch.tv/helix/streams?first=100");
                for (String login : chunk) {
                    url.append("&user_login=").append(URLEncoder.encode(login, StandardCharsets.UTF_8));
                }
                HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                        .timeout(Duration.ofSeconds(15))
                        .header("Client-Id", clientId)
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 401) {
                    accessToken = null; // token expired, get a new one next time
                    return;
                }
                if (res.statusCode() != 200) {
                    getLogger().warning("Twitch API returned HTTP " + res.statusCode());
                    return;
                }
                Matcher m = LOGIN_JSON.matcher(res.body());
                while (m.find()) {
                    liveLogins.add(m.group(1).toLowerCase(Locale.ROOT));
                }
            }

            Bukkit.getScheduler().runTask(this, () -> applyTwitchResults(liveLogins));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            getLogger().warning("Twitch check failed: " + e.getMessage());
        }
    }

    private String getToken(String clientId, String secret) throws Exception {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return accessToken;
        }
        String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                + "&grant_type=client_credentials";
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://id.twitch.tv/oauth2/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            getLogger().warning("Could not get a Twitch token (HTTP " + res.statusCode()
                    + "). Check twitch.client-id and twitch.client-secret in config.yml.");
            return null;
        }
        Matcher tm = TOKEN_JSON.matcher(res.body());
        if (!tm.find()) return null;
        long expires = 3600L;
        Matcher em = EXPIRES_JSON.matcher(res.body());
        if (em.find()) expires = Long.parseLong(em.group(1));
        accessToken = tm.group(1);
        tokenExpiresAt = System.currentTimeMillis() + Math.max(60L, expires - 300L) * 1000L;
        return accessToken;
    }

    /** Back on the main thread: update tags for anyone whose live state changed. */
    private void applyTwitchResults(Set<String> liveLogins) {
        boolean announce = getConfig().getBoolean("twitch.announce", false) && firstPollDone;
        for (Map.Entry<UUID, String> en : links.entrySet()) {
            UUID id = en.getKey();
            boolean nowLive = liveLogins.contains(en.getValue());
            boolean wasLive = twitchLive.contains(id);
            if (nowLive == wasLive) continue;

            if (nowLive) twitchLive.add(id);
            else twitchLive.remove(id);

            Player p = Bukkit.getPlayer(id);
            if (p != null) refresh(p);
            if (nowLive && announce) announceLive(id, en.getValue());
        }
        firstPollDone = true;
    }

    private void announceLive(UUID id, String login) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        if (name == null) name = login;
        String text = getConfig().getString("twitch.announce-message",
                "<dark_purple><bold>[LIVE]</bold></dark_purple> <white>%player%</white> "
                        + "<gray>is live on Twitch!</gray>");
        text = text.replace("%player%", name).replace("%twitch%", login);
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(text));
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (shouldShow(p.getUniqueId())) {
            apply(p, true);
            // give the TAB plugin a moment to load the player first
            Bukkit.getScheduler().runTaskLater(this, () -> syncTabPlugin(p, true), 40L);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getRawSlot() != BUTTON_SLOT) return;
        if (!p.hasPermission("livetag.use")) return;

        setLive(p, !live.contains(p.getUniqueId()));
        e.getInventory().setItem(BUTTON_SLOT, button(p));
    }

    // ------------------------------------------------------------ GUI

    private ItemStack button(Player p) {
        boolean on = live.contains(p.getUniqueId());
        ItemStack item = new ItemStack(on ? Material.PURPLE_DYE : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("LIVE: " + (on ? "ON" : "OFF"),
                        on ? NamedTextColor.DARK_PURPLE : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .decorate(TextDecoration.BOLD));
        meta.lore(List.of(Component.text("Click to turn it " + (on ? "off" : "on"),
                        NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new MenuHolder(), 27,
                Component.text("Stream Tag", NamedTextColor.DARK_PURPLE));
        inv.setItem(BUTTON_SLOT, button(p));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "toggle";

        if (sub.equals("reload")) {
            if (!sender.hasPermission("livetag.admin")) return true;
            reloadConfig();
            warnedNoCreds = false;
            accessToken = null;
            startPolling();
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (shouldShow(o.getUniqueId())) refresh(o);
            }
            sender.sendMessage(Component.text("LiveTag reloaded.", NamedTextColor.GREEN));
            return true;
        }

        if (sub.equals("link")) {
            handleLink(sender, args);
            return true;
        }
        if (sub.equals("unlink")) {
            handleUnlink(sender, args);
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!p.hasPermission("livetag.use")) {
            p.sendMessage(Component.text(msg("no-permission"), NamedTextColor.RED));
            return true;
        }

        switch (sub) {
            case "menu", "gui" -> openMenu(p);
            case "on" -> setLive(p, true);
            case "off" -> setLive(p, false);
            case "status" -> sendStatus(p);
            default -> setLive(p, !live.contains(p.getUniqueId()));
        }
        return true;
    }

    private void sendStatus(Player p) {
        UUID id = p.getUniqueId();
        String login = links.get(id);
        p.sendMessage(Component.text("Manual tag: " + (live.contains(id) ? "ON" : "OFF"),
                NamedTextColor.LIGHT_PURPLE));
        if (login == null) {
            p.sendMessage(Component.text("Twitch: not linked (ask staff to link your account).",
                    NamedTextColor.GRAY));
        } else {
            p.sendMessage(Component.text("Twitch: linked to " + login + " - "
                            + (twitchLive.contains(id) ? "LIVE now" : "offline"),
                    NamedTextColor.LIGHT_PURPLE));
        }
    }

    /** /live link <player> <twitch>   (staff)   or   /live link <twitch>   (if allowed in config) */
    private void handleLink(CommandSender sender, String[] args) {
        OfflinePlayer target;
        String login;

        if (args.length >= 3) {
            if (!sender.hasPermission("livetag.admin")) {
                sender.sendMessage(Component.text("Only staff can link other players.", NamedTextColor.RED));
                return;
            }
            target = Bukkit.getOfflinePlayerIfCached(args[1]);
            login = args[2];
            if (target == null) {
                sender.sendMessage(Component.text("Unknown player '" + args[1]
                        + "' - they must have joined the server before.", NamedTextColor.RED));
                return;
            }
        } else if (args.length == 2 && sender instanceof Player self) {
            boolean allowed = sender.hasPermission("livetag.admin")
                    || (getConfig().getBoolean("twitch.allow-self-link", false)
                    && sender.hasPermission("livetag.use"));
            if (!allowed) {
                sender.sendMessage(Component.text(
                        "Ask a staff member to link your Twitch account: /live link <player> <twitch>",
                        NamedTextColor.RED));
                return;
            }
            target = self;
            login = args[1];
        } else {
            sender.sendMessage(Component.text("Usage: /live link <player> <twitch name>", NamedTextColor.YELLOW));
            return;
        }

        if (!LOGIN_RULE.matcher(login).matches()) {
            sender.sendMessage(Component.text("That doesn't look like a valid Twitch name.", NamedTextColor.RED));
            return;
        }
        login = login.toLowerCase(Locale.ROOT);

        UUID id = target.getUniqueId();
        links.put(id, login);
        twitchLive.remove(id);
        saveData();
        sender.sendMessage(Component.text("Linked " + target.getName() + " to twitch.tv/" + login
                + ". Live status is checked every "
                + Math.max(30L, getConfig().getLong("twitch.check-interval-seconds", 60L)) + " seconds.",
                NamedTextColor.GREEN));

        // check right away instead of waiting for the next cycle
        Bukkit.getScheduler().runTaskAsynchronously(this, this::pollTwitch);
    }

    /** /live unlink <player>   (staff)   or   /live unlink   (if allowed in config) */
    private void handleUnlink(CommandSender sender, String[] args) {
        OfflinePlayer target;

        if (args.length >= 2) {
            if (!sender.hasPermission("livetag.admin")) {
                sender.sendMessage(Component.text("Only staff can unlink other players.", NamedTextColor.RED));
                return;
            }
            target = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Unknown player '" + args[1] + "'.", NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player self) {
            boolean allowed = sender.hasPermission("livetag.admin")
                    || getConfig().getBoolean("twitch.allow-self-link", false);
            if (!allowed) {
                sender.sendMessage(Component.text("Ask a staff member to unlink your Twitch account.",
                        NamedTextColor.RED));
                return;
            }
            target = self;
        } else {
            sender.sendMessage(Component.text("Usage: /live unlink <player>", NamedTextColor.YELLOW));
            return;
        }

        UUID id = target.getUniqueId();
        if (links.remove(id) == null) {
            sender.sendMessage(Component.text(target.getName() + " has no Twitch account linked.",
                    NamedTextColor.GRAY));
            return;
        }
        twitchLive.remove(id);
        saveData();
        Player online = Bukkit.getPlayer(id);
        if (online != null) refresh(online);
        sender.sendMessage(Component.text("Unlinked " + target.getName() + ".", NamedTextColor.GREEN));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("menu", "on", "off", "status"));
            subs.add("link");
            subs.add("unlink");
            if (sender.hasPermission("livetag.admin")) subs.add("reload");
            return subs;
        }
        if (args.length == 2 && sender.hasPermission("livetag.admin")
                && (args[0].equalsIgnoreCase("link") || args[0].equalsIgnoreCase("unlink"))) {
            List<String> names = new ArrayList<>();
            for (Player o : Bukkit.getOnlinePlayers()) names.add(o.getName());
            return names;
        }
        return List.of();
    }
}
