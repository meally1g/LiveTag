package dev.livetag;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.bukkit.util.Vector;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.SoundCategory;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LiveTag extends JavaPlugin implements Listener, TabExecutor {

    private static final String TEAM_NAME = "lt_live";
    private static final int BUTTON_SLOT = 13;
    private static final int FOLLOW_SLOT = 15;

    private static final Pattern LOGIN_RULE = Pattern.compile("^[A-Za-z0-9_]{3,25}$");
    private static final Pattern NAME_JSON = Pattern.compile(
            "\"user_login\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"user_name\"\\s*:\\s*\"([^\"]*)\"");
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

    // mascots
    private static final String MASCOT_TAG = "livetag_mascot";
    private static final int[] DEFAULT_PALETTE = {
            0xE63232, 0x32E6E6, 0xE6E632, 0x8C32E6, 0x32E632, 0xE68C32,
            0x328CE6, 0xE632E6, 0x8CE632, 0x3232E6, 0xE6328C, 0x32E68C,
            0x8C1C1C, 0x1C8C8C, 0x8C8C1C, 0x541C8C, 0x1C8C1C, 0x8C541C,
            0x1C548C, 0x8C1C8C, 0x548C1C, 0x1C1C8C, 0x8C1C54, 0x1C8C54,
            0xF27979, 0x79F2F2, 0xF2F279, 0xB679F2, 0x79F279, 0xF2B679,
            0x79B6F2, 0xF279F2, 0xB6F279, 0x7979F2, 0xF279B6, 0x79F2B6,
            0xFFBFBF, 0xBFFFFF, 0xFFFFBF, 0xDFBFFF, 0xBFFFBF, 0xFFDFBF,
            0xBFDFFF, 0xFFBFFF, 0xDFFFBF, 0xBFBFFF, 0xFFBFDF, 0xBFFFDF
    };
    private final Map<UUID, Mascot> mascots = new HashMap<>();
    private final Set<UUID> mascotOff = new HashSet<>();
    private final Set<UUID> preview = new HashSet<>();
    /** Streamers whose mascot stands still instead of following them. */
    private final Set<UUID> stay = new HashSet<>();
    /** Streamers who have been live on Twitch at least once - only they get a mascot. */
    private final Set<UUID> streamed = new HashSet<>();
    private final Set<UUID> idleDead = new HashSet<>(); // mascots that died from the streamer being offline too long
    private final Map<String, String> twitchNames = new ConcurrentHashMap<>(); // login -> Twitch display name
    /** A baby mascot of two streamers. */
    private record Baby(UUID id, UUID a, UUID b, long born) {
    }

    private final Map<UUID, Baby> babies = new HashMap<>();
    private final Map<UUID, Mascot> babyMascots = new HashMap<>();
    private final Set<UUID> babyChatOff = new HashSet<>();
    private final Set<UUID> babyStay = new HashSet<>();
    private final Map<String, String> pendingLinks = new HashMap<>(); // Minecraft name (lower case) -> twitch login, applied when they first join
    private final Set<UUID> introPending = new HashSet<>(); // streamers whose mascot gets its big entrance
    private final Map<UUID, Double> babyHp = new HashMap<>();
    private final Map<UUID, Long> inLove = new HashMap<>(); // streamer -> until when their mascot is in love
    private final Map<UUID, Long> lastLive = new ConcurrentHashMap<>(); // when each streamer was last seen live
    private final Map<UUID, Home> homes = new ConcurrentHashMap<>();
    /** Armour pieces a streamer has put on their mascot (replaces the default coloured outfit). */
    private final Map<UUID, Map<EquipmentSlot, ItemStack>> customArmor = new HashMap<>();
    private final Map<UUID, Integer> fed = new HashMap<>();
    /** Mascot health. Missing = full health. */
    private final Map<UUID, Double> hpMap = new HashMap<>();
    /** Dead mascots: owner -> time of death (epoch ms). A dead mascot leaves a grave. */
    private final Map<UUID, Long> deadSince = new HashMap<>();
    private final Map<UUID, Home> graves = new HashMap<>();
    /** Who killed a dead mascot (owner -> killer). Their player head brings it back. */
    private final Map<UUID, UUID> killers = new HashMap<>();
    private final Map<UUID, String> killerNames = new HashMap<>();
    /** How a mascot died when no player killed it (mob, lava, fall...). Those need a Totem of Undying. */
    private final Map<UUID, String> deathCause = new HashMap<>();
    private boolean dataDirty;
    private long lastSave;
    private final Map<UUID, Integer> colorIndex = new ConcurrentHashMap<>();
    private TwitchChat chat;
    private BukkitTask mascotTask;
    private BukkitTask walkTask;
    private Set<String> lastChannels = new HashSet<>();
    private boolean warnedSpawn;

    private File dataFile;

    /** Marker so we can recognise our own menu. */
    private static class MenuHolder implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }

    /** Marker for the /streamers list. */
    private static class StreamersHolder implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }

    // ------------------------------------------------------------ lifecycle

    /** The plugin used to be called LiveTag: take over its config and data on the first start as ChatMate. */
    private void migrateOldFolder() {
        try {
            File parent = getDataFolder().getParentFile();
            File old = parent == null ? null : new File(parent, "LiveTag");
            if (old == null || !old.isDirectory() || getDataFolder().exists()) return;
            getDataFolder().mkdirs();
            for (String name : new String[]{"config.yml", "data.yml"}) {
                File from = new File(old, name);
                if (from.isFile()) {
                    java.nio.file.Files.copy(from.toPath(), new File(getDataFolder(), name).toPath());
                }
            }
            getLogger().info("Copied the settings and data from plugins/LiveTag.");
        } catch (Exception e) {
            getLogger().warning("Could not copy the old LiveTag settings: " + e.getMessage());
        }
    }

    @Override
    public void onEnable() {
        migrateOldFolder();
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("live").setExecutor(this);
        getCommand("live").setTabCompleter(this);
        if (getCommand("mascot") != null) {
            getCommand("mascot").setExecutor(this);
            getCommand("mascot").setTabCompleter(this);
        }
        if (getCommand("streamers") != null) {
            getCommand("streamers").setExecutor(this);
            getCommand("streamers").setTabCompleter(this);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (shouldShow(p.getUniqueId())) apply(p, true);
        }

        startPolling();
        startMascots();
    }

    @Override
    public void onDisable() {
        if (pollTask != null) pollTask.cancel();
        stopMascots();
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
        for (String s : yml.getStringList("mascot-off")) {
            try {
                mascotOff.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String s : yml.getStringList("stay")) {
            try {
                stay.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String s : yml.getStringList("streamed")) {
            try {
                streamed.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String s2 : yml.getStringList("idle-dead")) {
            try {
                idleDead.add(UUID.fromString(s2));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ConfigurationSection tn = yml.getConfigurationSection("twitch-names");
        if (tn != null) {
            for (String key : tn.getKeys(false)) twitchNames.put(key, tn.getString(key, key));
        }
        ConfigurationSection bb = yml.getConfigurationSection("babies");
        if (bb != null) {
            for (String key : bb.getKeys(false)) {
                try {
                    UUID bid = UUID.fromString(key);
                    UUID pa = UUID.fromString(bb.getString(key + ".a", ""));
                    UUID pb = UUID.fromString(bb.getString(key + ".b", ""));
                    babies.put(bid, new Baby(bid, pa, pb, bb.getLong(key + ".born", System.currentTimeMillis())));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection ll = yml.getConfigurationSection("last-live");
        if (ll != null) {
            for (String key : ll.getKeys(false)) {
                try {
                    lastLive.put(UUID.fromString(key), ll.getLong(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection arm = yml.getConfigurationSection("armor");
        if (arm != null) {
            for (String key : arm.getKeys(false)) {
                ConfigurationSection a = arm.getConfigurationSection(key);
                if (a == null) continue;
                try {
                    UUID id = UUID.fromString(key);
                    Map<EquipmentSlot, ItemStack> mine = new EnumMap<>(EquipmentSlot.class);
                    for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                            EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND}) {
                        ItemStack it = a.getItemStack(slot.name());
                        if (it != null) mine.put(slot, it);
                    }
                    if (!mine.isEmpty()) customArmor.put(id, mine);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection hps = yml.getConfigurationSection("hp");
        if (hps != null) {
            for (String key : hps.getKeys(false)) {
                try {
                    hpMap.put(UUID.fromString(key), hps.getDouble(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection ds = yml.getConfigurationSection("dead");
        if (ds != null) {
            for (String key : ds.getKeys(false)) {
                try {
                    deadSince.put(UUID.fromString(key), ds.getLong(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection gs = yml.getConfigurationSection("grave");
        if (gs != null) {
            for (String key : gs.getKeys(false)) {
                ConfigurationSection g = gs.getConfigurationSection(key);
                if (g == null) continue;
                try {
                    graves.put(UUID.fromString(key), new Home(g.getString("world", ""),
                            g.getDouble("x"), g.getDouble("y"), g.getDouble("z"), (float) g.getDouble("yaw")));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection cs = yml.getConfigurationSection("cause");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                try {
                    deathCause.put(UUID.fromString(key), cs.getString(key, "It died"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection ks = yml.getConfigurationSection("killer");
        if (ks != null) {
            for (String key : ks.getKeys(false)) {
                ConfigurationSection k = ks.getConfigurationSection(key);
                if (k == null) continue;
                try {
                    UUID owner = UUID.fromString(key);
                    killers.put(owner, UUID.fromString(k.getString("id", "")));
                    String kn = k.getString("name");
                    if (kn != null) killerNames.put(owner, kn);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection fs = yml.getConfigurationSection("fed");
        if (fs != null) {
            for (String key : fs.getKeys(false)) {
                try {
                    fed.put(UUID.fromString(key), fs.getInt(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection hs = yml.getConfigurationSection("home");
        if (hs != null) {
            for (String key : hs.getKeys(false)) {
                ConfigurationSection h = hs.getConfigurationSection(key);
                if (h == null) continue;
                try {
                    homes.put(UUID.fromString(key), new Home(h.getString("world", ""),
                            h.getDouble("x"), h.getDouble("y"), h.getDouble("z"), (float) h.getDouble("yaw")));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection cols = yml.getConfigurationSection("colors");
        if (cols != null) {
            for (String key : cols.getKeys(false)) {
                try {
                    colorIndex.put(UUID.fromString(key), cols.getInt(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ConfigurationSection pend = yml.getConfigurationSection("pending-links");
        if (pend != null) {
            for (String key : pend.getKeys(false)) pendingLinks.put(key.toLowerCase(Locale.ROOT), pend.getString(key, ""));
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
        dataDirty = false;
        lastSave = System.currentTimeMillis();
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("live", live.stream().map(UUID::toString).toList());
        for (Map.Entry<UUID, String> en : links.entrySet()) {
            yml.set("links." + en.getKey(), en.getValue());
        }
        for (Map.Entry<String, String> pl : pendingLinks.entrySet()) {
            yml.set("pending-links." + pl.getKey(), pl.getValue());
        }
        yml.set("mascot-off", mascotOff.stream().map(UUID::toString).toList());
        yml.set("stay", stay.stream().map(UUID::toString).toList());
        yml.set("streamed", streamed.stream().map(UUID::toString).toList());
        for (Map.Entry<String, String> en : twitchNames.entrySet()) yml.set("twitch-names." + en.getKey(), en.getValue());
        for (Baby bby : babies.values()) {
            yml.set("babies." + bby.id() + ".a", bby.a().toString());
            yml.set("babies." + bby.id() + ".b", bby.b().toString());
            yml.set("babies." + bby.id() + ".born", bby.born());
        }
        yml.set("idle-dead", idleDead.stream().map(UUID::toString).toList());
        for (Map.Entry<UUID, Long> en : lastLive.entrySet()) yml.set("last-live." + en.getKey(), en.getValue());
        for (Map.Entry<UUID, Map<EquipmentSlot, ItemStack>> en : customArmor.entrySet()) {
            for (Map.Entry<EquipmentSlot, ItemStack> pe : en.getValue().entrySet()) {
                yml.set("armor." + en.getKey() + "." + pe.getKey().name(), pe.getValue());
            }
        }
        for (Map.Entry<UUID, Integer> en : fed.entrySet()) {
            yml.set("fed." + en.getKey(), en.getValue());
        }
        for (Map.Entry<UUID, Double> en : hpMap.entrySet()) {
            yml.set("hp." + en.getKey(), en.getValue());
        }
        for (Map.Entry<UUID, Long> en : deadSince.entrySet()) {
            yml.set("dead." + en.getKey(), en.getValue());
        }
        for (Map.Entry<UUID, String> en : deathCause.entrySet()) {
            yml.set("cause." + en.getKey(), en.getValue());
        }
        for (Map.Entry<UUID, UUID> en : killers.entrySet()) {
            yml.set("killer." + en.getKey() + ".id", en.getValue().toString());
            yml.set("killer." + en.getKey() + ".name", killerNames.get(en.getKey()));
        }
        for (Map.Entry<UUID, Home> en : graves.entrySet()) {
            String k = "grave." + en.getKey();
            Home h = en.getValue();
            yml.set(k + ".world", h.world());
            yml.set(k + ".x", h.x());
            yml.set(k + ".y", h.y());
            yml.set(k + ".z", h.z());
            yml.set(k + ".yaw", (double) h.yaw());
        }
        for (Map.Entry<UUID, Home> en : homes.entrySet()) {
            String k = "home." + en.getKey();
            Home h = en.getValue();
            yml.set(k + ".world", h.world());
            yml.set(k + ".x", h.x());
            yml.set(k + ".y", h.y());
            yml.set(k + ".z", h.z());
            yml.set(k + ".yaw", (double) h.yaw());
        }
        for (Map.Entry<UUID, Integer> en : colorIndex.entrySet()) {
            yml.set("colors." + en.getKey(), en.getValue());
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

        // TAB also controls the name above the head, so the tag has to be set there as well
        if (getConfig().getBoolean("tab-nametag", true)) {
            String tag = "tab player " + p.getName() + " tagprefix";
            if (on) tag += " " + getConfig().getString("tab-nametag-prefix", "&5&l[LIVE] &r");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), tag);
        }
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
                Matcher nm = NAME_JSON.matcher(res.body());
                while (nm.find()) {
                    String lg = nm.group(1).toLowerCase(Locale.ROOT);
                    liveLogins.add(lg);
                    if (!nm.group(2).isBlank() && !nm.group(2).equals(twitchNames.put(lg, nm.group(2)))) dataDirty = true;
                }
                Matcher m = LOGIN_JSON.matcher(res.body());
                while (m.find()) {
                    liveLogins.add(m.group(1).toLowerCase(Locale.ROOT));
                }
            }

            Bukkit.getScheduler().runTask(this, () -> {
                for (Mascot mm : mascots.values()) mm.nameDirty = true; // pick up display names
                applyTwitchResults(liveLogins);
            });
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

    /**
     * A streamer who goes live (or joins while live) gets their mascot back by their side:
     * it stops staying, switches back on if it was off, and jumps to them.
     */
    private void summonMascot(UUID id) {
        if (!getConfig().getBoolean("mascot.come-when-live", true)) return;
        if (deadSince.containsKey(id)) return; // a dead mascot stays in its grave
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        boolean changed = stay.remove(id);
        homes.remove(id);
        changed |= mascotOff.remove(id);
        if (changed) saveData();
        Mascot m = mascots.get(id);
        if (m == null || m.isGrave || !m.valid() || m.leashProxy != null) return; // a missing one spawns next to them on its own
        Location to = mascotLocation(p);
        if (m.body.getWorld() == to.getWorld() && m.body.getLocation().distanceSquared(to) < 9.0) return; // already close
        m.body.teleport(to);
        if (m.board != null && m.board.isValid()) m.board.teleport(to.clone().add(0, boardHeight(), 0));
        to.getWorld().spawnParticle(Particle.POOF, to.clone().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
    }

    /** Back on the main thread: update tags for anyone whose live state changed. */
    private void applyTwitchResults(Set<String> liveLogins) {
        boolean announce = getConfig().getBoolean("twitch.announce", false) && firstPollDone;
        boolean firstStream = false;
        for (Map.Entry<UUID, String> en : links.entrySet()) {
            UUID id = en.getKey();
            boolean nowLive = liveLogins.contains(en.getValue());
            boolean wasLive = twitchLive.contains(id);
            if (nowLive) {
                lastLive.put(id, System.currentTimeMillis());
                dataDirty = true;
            }
            if (nowLive == wasLive) continue;

            if (nowLive) {
                twitchLive.add(id);
                summonMascot(id);
                if (idleDead.contains(id)) reviveMascot(id, "because its streamer went live again!");
                if (streamed.add(id)) { // their very first stream
                    dataDirty = true;
                    // make sure the mascot really shows up next to them, with no commands needed
                    colorFor(id); // random colour no other streamer has (unless staff already chose one)
                    firstStream = true;
                    introPending.add(id);
                    mascotOff.remove(id);
                    stay.remove(id);
                    homes.remove(id);
                    lastLive.put(id, System.currentTimeMillis());
                    Mascot stale = mascots.remove(id);
                    if (stale != null) discard(stale);
                    Player first = Bukkit.getPlayer(id);
                    if (first != null) {
                        first.sendMessage(Component.text(
                                "You're live for the first time - your chat mascot has arrived!",
                                NamedTextColor.LIGHT_PURPLE));
                    }
                }
            } else {
                twitchLive.remove(id);
            }

            Player p = Bukkit.getPlayer(id);
            if (p != null) refresh(p);
            if (nowLive && announce) announceLive(id, en.getValue());
        }
        firstPollDone = true;
        if (firstStream) tickMascots(); // spawn it right away instead of waiting for the next tick
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
        String waiting = pendingLinks.remove(p.getName().toLowerCase(Locale.ROOT));
        if (waiting != null && LOGIN_RULE.matcher(waiting).matches()) {
            String previous = links.put(p.getUniqueId(), waiting);
            if (!waiting.equals(previous)) streamed.remove(p.getUniqueId());
            saveData();
            Bukkit.getScheduler().runTaskAsynchronously(this, this::pollTwitch);
            getLogger().info("Linked " + p.getName() + " to twitch.tv/" + waiting + " (set up before they joined).");
        }
        if (shouldShow(p.getUniqueId())) {
            apply(p, true);
            // give the TAB plugin a moment to load the player first
            Bukkit.getScheduler().runTaskLater(this, () -> syncTabPlugin(p, true), 40L);
        }
        if (twitchLive.contains(p.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> summonMascot(p.getUniqueId()), 40L);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("livetag.use")) return;

        if (e.getRawSlot() == BUTTON_SLOT) {
            setLive(p, !live.contains(p.getUniqueId()));
            e.getInventory().setItem(BUTTON_SLOT, button(p));
        } else if (e.getRawSlot() == FOLLOW_SLOT) {
            if (!canUseMascot(p)) return;
            setFollow(p, stay.contains(p.getUniqueId()));
            e.getInventory().setItem(FOLLOW_SLOT, followButton(p));
        }
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

    private ItemStack followButton(Player p) {
        boolean following = !stay.contains(p.getUniqueId());
        ItemStack item = new ItemStack(following ? Material.LEAD : Material.ARMOR_STAND);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Mascot: " + (following ? "FOLLOWING" : "STAYING"),
                        following ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .decorate(TextDecoration.BOLD));
        meta.lore(List.of(Component.text(following ? "Click to make it stay where it is"
                                : "Click to make it follow you again", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------ /mascot locate

    private Component locLine(String label, Location loc) {
        World w = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        String world = w == null ? "?" : w.getKey().asString();
        Component where = Component.text(world + "  " + x + " " + y + " " + z, NamedTextColor.AQUA)
                .decoration(TextDecoration.UNDERLINED, true)
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Component.text("Click to teleport there", NamedTextColor.YELLOW)));
        if (w != null) {
            where = where.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                    "/execute in " + world + " run tp @s " + x + " " + y + " " + z));
        }
        return Component.text(label + "  ", NamedTextColor.GRAY).append(where);
    }

    /** /mascot locate [player]  (staff): where is somebody's mascot? Clickable coordinates. */
    private void mascotLocate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("livetag.admin")) {
            sender.sendMessage(Component.text(msg("no-permission"), NamedTextColor.RED));
            return;
        }
        if (args.length < 1 || !args[0].equalsIgnoreCase("locate")) {
            sender.sendMessage(Component.text("Usage: /mascot locate [player]", NamedTextColor.YELLOW));
            return;
        }
        Set<UUID> who = new LinkedHashSet<>();
        if (args.length >= 2) {
            OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (t == null) {
                sender.sendMessage(Component.text("Unknown player '" + args[1] + "'.", NamedTextColor.RED));
                return;
            }
            who.add(t.getUniqueId());
        } else {
            who.addAll(mascots.keySet());
            who.addAll(graves.keySet());
        }
        int found = 0;
        for (UUID id : who) {
            String name = Bukkit.getOfflinePlayer(id).getName();
            if (name == null) name = id.toString().substring(0, 8);
            Mascot m = mascots.get(id);
            Location loc = null;
            String kind = "mascot";
            if (m != null && m.valid()) {
                if (m.isGrave) {
                    kind = "grave";
                    if (m.tomb != null) loc = m.tomb.getLocation();
                } else if (m.body != null) {
                    loc = m.body.getLocation();
                }
            }
            if (loc == null && deadSince.containsKey(id) && graves.get(id) != null) {
                loc = graves.get(id).toLocation();
                kind = "grave";
            }
            if (loc == null && stay.contains(id) && homes.get(id) != null) {
                loc = homes.get(id).toLocation();
                kind = "staying spot (not spawned right now)";
            }
            if (loc != null && loc.getWorld() != null) {
                sender.sendMessage(locLine(name + " - " + kind + ":", loc));
                found++;
            } else if (args.length >= 2) {
                Player online = Bukkit.getPlayer(id);
                sender.sendMessage(Component.text(name + " has no mascot out right now"
                        + (links.containsKey(id) ? "" : " (not linked to Twitch)")
                        + (online == null ? " - the owner is offline." : "."), NamedTextColor.GRAY));
                found++;
            }
        }
        for (Baby b : babies.values()) {
            if (args.length >= 2 && !who.contains(b.a()) && !who.contains(b.b())) continue;
            Mascot bm = babyMascots.get(b.id());
            if (bm != null && bm.body != null && bm.body.isValid()) {
                sender.sendMessage(locLine("Baby of " + streamerName(b.a()) + " + " + streamerName(b.b()) + ":",
                        bm.body.getLocation()));
                found++;
            }
        }
        if (found == 0) sender.sendMessage(Component.text("No mascots found.", NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------ /streamers

    private static String ago(long ms) {
        long min = Math.max(1, ms / 60_000L);
        if (min < 60) return min + " min ago";
        long h = min / 60;
        if (h < 48) return h + " h ago";
        return (h / 24) + " days ago";
    }

    /** A list of every linked streamer: their head, their Twitch, and a dot that is red while they are live. */
    private void openStreamers(Player viewer) {
        List<UUID> ids = new ArrayList<>(links.keySet());
        Map<UUID, String> names = new HashMap<>();
        for (UUID id : ids) {
            String n = Bukkit.getOfflinePlayer(id).getName();
            names.put(id, n == null ? "Unknown" : n);
        }
        ids.sort((x, y) -> {
            boolean lx = twitchLive.contains(x);
            boolean ly = twitchLive.contains(y);
            if (lx != ly) return lx ? -1 : 1; // live streamers first
            return names.get(x).compareToIgnoreCase(names.get(y));
        });
        int shown = Math.min(ids.size(), 54);
        int rows = Math.max(1, (shown + 8) / 9);
        long liveCount = ids.stream().filter(twitchLive::contains).count();
        Inventory inv = Bukkit.createInventory(new StreamersHolder(), rows * 9,
                Component.text("Streamers  ", NamedTextColor.DARK_PURPLE)
                        .append(Component.text("\u25CF " + liveCount + " live", NamedTextColor.RED)));
        long now = System.currentTimeMillis();
        for (int i = 0; i < shown; i++) {
            UUID id = ids.get(i);
            String login = links.get(id);
            boolean live = twitchLive.contains(id);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            meta.displayName(Component.text("\u25CF ", live ? NamedTextColor.RED : NamedTextColor.DARK_GRAY)
                    .append(Component.text(names.get(id), NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            String display = twitchNames.getOrDefault(login.toLowerCase(Locale.ROOT), login);
            lore.add(Component.text("Twitch: ", NamedTextColor.GRAY)
                    .append(Component.text(display, NamedTextColor.LIGHT_PURPLE))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(live
                    ? Component.text("LIVE now", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
                    : Component.text("Offline", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            Long seen = lastLive.get(id);
            if (!live && seen != null) {
                lore.add(Component.text("Last live " + ago(now - seen), NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.empty());
            lore.add(Component.text("Click for the channel link", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            head.setItemMeta(meta);
            inv.setItem(i, head);
        }
        viewer.openInventory(inv);
    }

    @EventHandler
    public void onStreamersClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof StreamersHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getRawSlot() < 0 || e.getRawSlot() >= e.getView().getTopInventory().getSize()) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() != Material.PLAYER_HEAD || !(it.getItemMeta() instanceof SkullMeta sm)) return;
        OfflinePlayer owner = sm.getOwningPlayer();
        String login = owner == null ? null : links.get(owner.getUniqueId());
        if (login == null) return;
        p.closeInventory();
        String url = "https://twitch.tv/" + login;
        p.sendMessage(Component.text("Twitch: ", NamedTextColor.GRAY)
                .append(Component.text(url, NamedTextColor.LIGHT_PURPLE)
                        .decoration(TextDecoration.UNDERLINED, true)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url))));
    }

    @EventHandler
    public void onStreamersDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof StreamersHolder) e.setCancelled(true);
    }

    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new MenuHolder(), 27,
                Component.text("Stream Tag", NamedTextColor.DARK_PURPLE));
        inv.setItem(BUTTON_SLOT, button(p));
        inv.setItem(FOLLOW_SLOT, followButton(p));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (cmd.getName().equalsIgnoreCase("mascot")) {
            mascotLocate(sender, args);
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("streamers")) {
            if (sender instanceof Player viewer) {
                openStreamers(viewer);
            } else {
                sender.sendMessage("Players only.");
            }
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "toggle";

        if (sub.equals("reload")) {
            if (!sender.hasPermission("livetag.admin")) return true;
            reloadConfig();
            warnedNoCreds = false;
            accessToken = null;
            startPolling();
            stopMascots();
            startMascots();
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (shouldShow(o.getUniqueId())) refresh(o);
            }
            sender.sendMessage(Component.text("ChatMate reloaded.", NamedTextColor.GREEN));
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
        if (sub.equals("mascot")) {
            handleMascot(sender, args);
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
            if (target == null) { // never joined: remember the link and apply it when they first join
                if (!LOGIN_RULE.matcher(login).matches() || !args[1].matches("[A-Za-z0-9_.]{1,16}")) {
                    sender.sendMessage(Component.text("That doesn't look like a valid Minecraft or Twitch name.",
                            NamedTextColor.RED));
                    return;
                }
                pendingLinks.put(args[1].toLowerCase(Locale.ROOT), login.toLowerCase(Locale.ROOT));
                saveData();
                sender.sendMessage(Component.text(args[1] + " hasn't joined yet. They will be linked to twitch.tv/"
                        + login.toLowerCase(Locale.ROOT) + " the moment they first join.", NamedTextColor.GREEN));
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
        String previous = links.put(id, login);
        twitchLive.remove(id);
        if (!login.equals(previous)) streamed.remove(id); // a different channel has to stream first
        // the colour is picked at random on their first stream (or by staff with /live mascot color)
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
                if (pendingLinks.remove(args[1].toLowerCase(Locale.ROOT)) != null) {
                    saveData();
                    sender.sendMessage(Component.text("Removed the waiting link for " + args[1] + ".", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Unknown player '" + args[1] + "'.", NamedTextColor.RED));
                }
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
        streamed.remove(id);
        saveData();
        Player online = Bukkit.getPlayer(id);
        if (online != null) refresh(online);
        sender.sendMessage(Component.text("Unlinked " + target.getName() + ".", NamedTextColor.GREEN));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        if (cmd.getName().equalsIgnoreCase("streamers")) return List.of();
        if (cmd.getName().equalsIgnoreCase("mascot")) {
            if (!sender.hasPermission("livetag.admin")) return List.of();
            if (args.length == 1) return List.of("locate");
            if (args.length == 2) {
                List<String> out = new ArrayList<>();
                for (UUID id : links.keySet()) {
                    String n = Bukkit.getOfflinePlayer(id).getName();
                    if (n != null && n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(n);
                }
                return out;
            }
            return List.of();
        }
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("menu", "on", "off", "status"));
            subs.add("link");
            subs.add("unlink");
            subs.add("mascot");
            if (sender.hasPermission("livetag.admin")) subs.add("reload");
            return subs;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mascot")) {
            return List.of("on", "off", "follow", "here", "preview", "revive", "color", "give", "debug", "test", "baby", "intro");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("mascot")
                && (args[1].equalsIgnoreCase("color") || args[1].equalsIgnoreCase("revive") || args[1].equalsIgnoreCase("give"))
                && sender.hasPermission("livetag.admin")) {
            List<String> names = new ArrayList<>();
            for (Player o : Bukkit.getOnlinePlayers()) names.add(o.getName());
            return names;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("mascot") && args[1].equalsIgnoreCase("color")
                && sender.hasPermission("livetag.admin")) {
            return colorNames();
        }
        if (args.length == 2 && sender.hasPermission("livetag.admin")
                && (args[0].equalsIgnoreCase("link") || args[0].equalsIgnoreCase("unlink"))) {
            List<String> names = new ArrayList<>();
            for (Player o : Bukkit.getOnlinePlayers()) names.add(o.getName());
            return names;
        }
        return List.of();
    }

    // ------------------------------------------------------------ mascots

    /** One chat mascot: a mannequin (player-style figure) plus a floating text board. */
    private static final class Mascot {
        record Line(Component text, long expiresAt) {
        }

        final UUID owner;
        String login; // Twitch channel, may be null (preview)
        final int rgb;
        Mannequin body;
        TextDisplay board;
        final Deque<Line> lines = new ArrayDeque<>();
        boolean dirty = true;
        Boolean liveShown;
        boolean nameDirty = true;
        LivingEntity leashProxy; // invisible leashable stand-in the lead is tied to
        UUID leashPlayer;
        Location walkGoal; // where it is currently walking to (null = not walking)
        int stuck;
        long lastRegenFx;
        long lastSound;
        double stopDist = 2.5; // how close it walks to its goal
        double vy; // vertical speed while falling
        double fallDist; // blocks fallen so far
        int farYTicks; // how long its owner has been far above or below it
        int seekTicks; // how long it has been trying to reach a ladder
        long jumpHeldSince; // when the owner started holding the jump key
        long sneakSince; // when the owner started crouching
        boolean airborne; // for the jump and landing sounds
        double stride; // distance walked since its last step sound
        long nextIdle; // when it may play an idle sound again
        boolean berserk; // low health: attacks everybody
        long lastAttack;
        long lastFireHit; // fire and lava only hurt every half second, like in vanilla
        long introHideUntil; // entrance animation: name and chat stay hidden until then
        float fade = 1f; // 1 = name and chat fully shown, 0 = faded out
        boolean wantHidden; // the owner has been crouching long enough: fade out
        boolean nameShown = true;
        long contactSince; // when a player started looking at it (0 = nobody)
        boolean crouching;
        long tpAt; // when a delayed teleport will happen (0 = none planned)
        long hopUntil; // hops while it keeps walking (owner holds jump)
        long partyUntil; // jumps on the spot until this time (Twitch events)
        double kx; // knockback speed (decays every tick)
        double kz;
        boolean baby; // a baby of two streamers: shows both chats
        UUID babyId; // baby only: its id in the babies map
        boolean chatOff; // baby only: right-click toggles its chat
        String login2; // the other parent's Twitch channel (babies only)
        double sizeFactor = 1.0; // 0.5 for babies

        boolean listensTo(String channel) {
            return (login != null && login.equals(channel)) || (login2 != null && login2.equals(channel));
        }

        boolean leather = true; // wearing the default coloured leather outfit (no coloured skin)
        long lastDamage;
        long lastRegen;
        int ticks;
        // grave (a dead mascot)
        boolean isGrave;
        Interaction tomb;
        ItemDisplay skull;
        Component lastGrave;

        Mascot(UUID owner, String login, int rgb) {
            this.owner = owner;
            this.login = login;
            this.rgb = rgb;
        }

        boolean valid() {
            if (board == null || !board.isValid()) return false;
            if (isGrave) return tomb != null && tomb.isValid() && skull != null && skull.isValid();
            return body != null && body.isValid();
        }

        void remove() {
            if (body != null) body.remove();
            if (board != null) board.remove();
            if (tomb != null) tomb.remove();
            if (skull != null) skull.remove();
            if (leashProxy != null) leashProxy.remove();
            leashProxy = null;
            body = null;
            board = null;
            tomb = null;
            skull = null;
        }
    }

    private void startMascots() {
        if (!getConfig().getBoolean("mascot.enabled", true)) return;
        chat = new TwitchChat(getLogger());
        chat.start();
        mascotTask = Bukkit.getScheduler().runTaskTimer(this, this::tickMascots, 40L, 5L);
        walkTask = Bukkit.getScheduler().runTaskTimer(this, this::walkTick, 40L, 1L);
    }

    /** Throws a mascot away, handing back its lead first. */
    private void discard(Mascot m) {
        if (m.leashProxy != null) dropLeash(m);
        m.remove();
    }

    private void stopMascots() {
        if (mascotTask != null) {
            mascotTask.cancel();
            mascotTask = null;
        }
        if (walkTask != null) {
            walkTask.cancel();
            walkTask = null;
        }
        if (chat != null) {
            chat.stop();
            chat = null;
        }
        for (Mascot m : new ArrayList<>(mascots.values())) discard(m);
        for (Mascot bm : new ArrayList<>(babyMascots.values())) discard(bm);
        babyMascots.clear();
        mascots.clear();
        lastChannels = new HashSet<>();
    }

    private int[] palette() {
        List<Integer> out = new ArrayList<>();
        for (String s : getConfig().getStringList("mascot.colors")) {
            try {
                out.add(Integer.parseInt(s.replace("#", "").trim(), 16));
            } catch (NumberFormatException ignored) {
            }
        }
        if (out.isEmpty()) return DEFAULT_PALETTE;
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** Every streamer gets their own colour: a random palette colour nobody else has. */
    private int colorFor(UUID id) {
        int[] pal = palette();
        Integer idx = colorIndex.get(id);
        if (idx == null) {
            Set<Integer> used = new HashSet<>(colorIndex.values());
            // prefer free colours whose skin has been filled in, so the mascot really gets its skin
            List<Map<?, ?>> skinList = getConfig().getMapList("mascot.skins");
            List<Integer> free = new ArrayList<>();
            List<Integer> freeSkinned = new ArrayList<>();
            for (int i = 0; i < pal.length; i++) {
                if (used.contains(i)) continue;
                free.add(i);
                if (i < skinList.size() && !text(skinList.get(i).get("value")).isEmpty()) freeSkinned.add(i);
            }
            if (!freeSkinned.isEmpty()) free = freeSkinned;
            int pick = free.isEmpty()
                    ? ThreadLocalRandom.current().nextInt(pal.length) // every colour taken: reuse one
                    : free.get(ThreadLocalRandom.current().nextInt(free.size()));
            colorIndex.put(id, pick);
            idx = pick;
            saveData();
        }
        return pal[idx % pal.length];
    }

    /** A sound volume, scaled by the master volume and the volume of its group in the config. */
    private float sfx(String group, float base) {
        double master = getConfig().getDouble("mascot.volume.master", 1.0);
        double part = getConfig().getDouble("mascot.volume." + group, 1.0);
        return (float) Math.max(0.0, base * master * part);
    }

    private double mascotScale() {
        return Math.max(0.1, Math.min(4.0, getConfig().getDouble("mascot.scale", 0.7)));
    }

    /** Chat board height, shrunk together with the mascot. */
    private double boardHeight() {
        return getConfig().getDouble("mascot.board-height", 3.0) * mascotScale();
    }

    private double maxHp() {
        return Math.max(1.0, getConfig().getDouble("mascot.health", 20.0));
    }

    private double hp(UUID id) {
        return Math.min(maxHp(), hpMap.getOrDefault(id, maxHp()));
    }

    private void tickMascots() {
        long now = System.currentTimeMillis();

        // 1. Twitch chat -> mascot boards / revive votes
        if (chat != null) {
            TwitchChat.Message msg;
            int n = 0;
            while (n++ < 100 && (msg = chat.poll()) != null) {
                routeChat(msg, now);
            }
        }

        // 2. who should have a mascot
        boolean always = getConfig().getBoolean("mascot.always-visible", true);
        Set<UUID> wanted = new HashSet<>();
        boolean needFirstStream = getConfig().getBoolean("mascot.require-first-stream", true);
        for (UUID id : (always ? links.keySet() : twitchLive)) {
            if (needFirstStream && !streamed.contains(id)) continue;
            if (!mascotOff.contains(id)) wanted.add(id);
        }
        wanted.addAll(preview);

        // a streamer who hasn't been live for too long loses their mascot
        double idleHours = getConfig().getDouble("mascot.inactivity-death-hours", 48);
        if (idleHours > 0) {
            long limit = (long) (idleHours * 3600_000L);
            for (UUID id : new ArrayList<>(links.keySet())) {
                if (!streamed.contains(id) || twitchLive.contains(id) || deadSince.containsKey(id)) continue;
                Long seen = lastLive.get(id);
                if (seen == null) { // streamed before this feature existed: start the clock now
                    lastLive.put(id, now);
                    dataDirty = true;
                    continue;
                }
                Mascot cm = mascots.get(id);
                if (now - seen > limit && cm != null && !cm.isGrave && cm.valid()) {
                    killMascot(cm, null, "No stream for " + (long) idleHours + " hours");
                    idleDead.add(id);
                    dataDirty = true;
                }
            }
        }

        Set<UUID> present = new HashSet<>();
        Set<String> channels = new HashSet<>();
        boolean offlineChat = getConfig().getBoolean("mascot.offline-chat", true);
        for (UUID id : new ArrayList<>(wanted)) {
            Player p = Bukkit.getPlayer(id);
            if (deadSince.containsKey(id) && graves.get(id) == null) { // dead with no grave spot: would stay invisible
                deadSince.remove(id);
                dataDirty = true;
            }
            boolean grave = deadSince.containsKey(id);

            Location leashed = null;
            Mascot leashMascot = mascots.get(id);
            if (!grave && leashMascot != null && !leashMascot.isGrave) leashed = leashTarget(leashMascot);

            Location target = null;
            if (grave) {
                Home g = graves.get(id);
                if (g != null) target = g.toLocation();
            } else if (leashed != null) {
                target = leashed;
            } else if (stay.contains(id)) {
                Home h = homes.get(id);
                if (h != null) {
                    target = h.toLocation();
                } else if (p != null) { // staying but no spot saved yet: stay right here
                    target = mascotLocation(p);
                    homes.put(id, Home.of(target));
                }
            } else if (p != null) {
                target = mascotLocation(p);
            }
            if (target == null || target.getWorld() == null) continue;
            present.add(id);

            // never force-load far away chunks just for a mascot
            if (!target.getWorld().isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) continue;

            Mascot m = mascots.get(id);
            // killed in a way we did not see (for example /kill): treat it as a normal death
            if (m != null && !m.isGrave && m.body != null && m.body.isDead()) {
                killMascot(m, null, null);
                continue;
            }
            if (m == null || m.isGrave != grave || !m.valid()) {
                if (m != null) discard(m);
                m = grave ? createGrave(id, target) : createMascot(id, target);
                if (m == null) {
                    mascots.remove(id);
                    continue;
                }
                mascots.put(id, m);
                if (!grave && introPending.remove(id)) playIntro(m);
            }
            m.login = links.get(id);
            m.ticks++;

            if (grave) {
                renderGrave(m, now);
                continue;
            }

            boolean isLive = twitchLive.contains(id) || preview.contains(id);

            // Regeneration potion effect (vanilla rate: 1 health every 50 >> level ticks)
            PotionEffect rg = m.body.getPotionEffect(PotionEffectType.REGENERATION);
            if (rg != null && now - m.lastRegenFx >= (50L >> Math.min(rg.getAmplifier(), 5)) * 50L) {
                healMascot(m, 1.0);
                m.lastRegenFx = now;
            }

            // slow natural healing
            double max = maxHp();
            double cur = hp(id);
            long regenMs = Math.max(1L, getConfig().getLong("mascot.regen-seconds", 10L)) * 1000L;
            if (cur < max && now - m.lastDamage > regenMs && now - m.lastRegen > regenMs) {
                hpMap.put(id, Math.min(max, cur + 1.0));
                m.lastRegen = now;
                m.nameDirty = true;
                dataDirty = true;
            }

            if (m.fade >= 0.5f && (m.liveShown == null || m.liveShown != isLive || m.nameDirty)) {
                m.liveShown = isLive;
                m.nameDirty = false;
                updateNameplate(m.body, id, m.rgb, isLive);
            }
            handleBoat(m, id, p);

            // crouches when its owner has been crouching for a while
            double crouchAfter = getConfig().getDouble("mascot.crouch-after-seconds", 1.0);
            boolean wantCrouch = false;
            if (crouchAfter >= 0 && p != null && p.isSneaking()) {
                if (m.sneakSince == 0) m.sneakSince = now;
                wantCrouch = now - m.sneakSince >= (long) (crouchAfter * 1000L);
            } else {
                m.sneakSince = 0;
            }
            double hideAfter = getConfig().getDouble("mascot.hide-after-crouch-seconds", 5.0);
            m.wantHidden = hideAfter > 0 && m.sneakSince != 0 && now - m.sneakSince >= (long) (hideAfter * 1000L);
            // hops on the spot while its owner holds the jump key
            double jumpAfter = getConfig().getDouble("mascot.jump-after-seconds", 2.0);
            if (jumpAfter >= 0 && p != null && p.getCurrentInput().isJump()) {
                if (m.jumpHeldSince == 0) m.jumpHeldSince = now;
                if (now - m.jumpHeldSince >= (long) (jumpAfter * 1000L)) {
                    m.hopUntil = Math.max(m.hopUntil, now + 700L);
                }
            } else {
                m.jumpHeldSince = 0;
            }
            if (wantCrouch != m.crouching && !m.body.isInsideVehicle()) {
                m.crouching = wantCrouch;
                m.body.setPose(wantCrouch ? Pose.SNEAKING : Pose.STANDING, wantCrouch);
            }
            if (!grave && !m.body.isInsideVehicle()) {
                // walkTick (every tick) walks it to its goal, handles gravity and fall damage
                boolean staying = leashed == null && stay.contains(id);
                m.walkGoal = leashed != null ? leashed : staying ? target : p.getLocation();
                m.stopDist = staying ? 0.3 : getConfig().getDouble("mascot.follow-distance", 2.5);
                berserk(m, id, now);
            } else {
                m.walkGoal = null;
                moveMascot(m, target);
            }
            renderBoard(m, now);
            if (m.login != null && (offlineChat || twitchLive.contains(id))) channels.add(m.login);
        }

        tickBabies(now, channels, offlineChat);

        // 3. remove mascots that should not exist (disabled, or follower whose owner left)
        Iterator<Map.Entry<UUID, Mascot>> it = mascots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Mascot> en = it.next();
            if (!present.contains(en.getKey())) {
                discard(en.getValue());
                it.remove();
            }
        }

        // 4. only listen to the chat of streamers who are live
        if (chat != null && !channels.equals(lastChannels)) {
            lastChannels = channels;
            chat.setChannels(channels);
        }

        // 5. save changed data now and then (not on every hit)
        if (dataDirty && now - lastSave > 5000L) saveData();
    }

    private void routeChat(TwitchChat.Message msg, long now) {
        if (msg.event() != null) {
            handleTwitchEvent(msg, now);
            return;
        }
        String name = msg.name().replaceAll("\\p{Cntrl}", "").trim();
        if (name.length() > 25) name = name.substring(0, 25);
        String text = cleanMessage(msg.text(), name);
        if (text == null || name.isEmpty()) return;

        long ttl = Math.max(5L, getConfig().getLong("mascot.message-seconds", 25L)) * 1000L;
        int max = Math.max(1, getConfig().getInt("mascot.max-lines", 6));
        // Chat is shown as plain text only - never parsed as formatting.
        for (Mascot m : allMascots()) {
            if (!m.isGrave && !m.chatOff && m.listensTo(msg.channel())) {
                addLine(m, bubbleLine(name, text, m.rgb), now + ttl, max);
                playChatSound(m, text, now);
            }
        }
    }

    /** A sub, resub, gift, raid or cheer: shown on the board, with a sound, particles and a note to the streamer. */
    private void handleTwitchEvent(TwitchChat.Message msg, long now) {
        ConfigurationSection cs = getConfig().getConfigurationSection("mascot.events");
        if (cs != null && !cs.getBoolean("enabled", true)) return;
        String type = msg.event();
        if (type.equals("cheer") && msg.amount() < (cs == null ? 100 : cs.getInt("cheer-min-bits", 100))) return;
        long ttl = (cs == null ? 40L : cs.getLong("show-seconds", 40L)) * 1000L;
        int max = Math.max(1, getConfig().getInt("mascot.max-lines", 5));

        NamedTextColor col;
        String icon;
        String soundKey;
        Particle particle;
        switch (type) {
            case "raid" -> {
                col = NamedTextColor.RED;
                icon = "\u26A1 ";
                soundKey = "raid";
                particle = Particle.FLAME;
            }
            case "subgift", "submysterygift" -> {
                col = NamedTextColor.LIGHT_PURPLE;
                icon = "\uD83C\uDF81 ";
                soundKey = "gift";
                particle = Particle.HEART;
            }
            case "cheer" -> {
                col = NamedTextColor.AQUA;
                icon = "\u25C6 ";
                soundKey = "cheer";
                particle = Particle.NOTE;
            }
            default -> {
                col = NamedTextColor.GOLD;
                icon = "\u2605 ";
                soundKey = type; // sub / resub
                particle = Particle.HEART;
            }
        }
        Component line = Component.text(icon + msg.text(), col, TextDecoration.BOLD);
        String defaultSound = switch (soundKey) {
            case "raid" -> "entity.ender_dragon.growl";
            case "gift" -> "entity.player.levelup";
            case "cheer" -> "entity.experience_orb.pickup";
            default -> "entity.player.levelup";
        };
        String sound = cs == null ? defaultSound : cs.getString("sounds." + soundKey, defaultSound);

        for (Mascot m : allMascots()) {
            if (m.isGrave || m.baby || m.chatOff || !(msg.channel().equals("preview:" + m.owner) || m.listensTo(msg.channel()))) continue;
            addLine(m, line, now + ttl, max);
            if (cs == null || cs.getDouble("jump-seconds", 8.0) > 0) {
                m.partyUntil = Math.max(m.partyUntil, now + (long) ((cs == null ? 8.0 : cs.getDouble("jump-seconds", 8.0)) * 1000L));
            }
            if (m.body != null && m.body.isValid()) {
                Location at = m.body.getLocation();
                if (sound != null && !sound.isBlank()) {
                    at.getWorld().playSound(at, sound.trim(), SoundCategory.NEUTRAL, sfx("events", 1f), 1f);
                }
                at.getWorld().spawnParticle(particle, at.clone().add(0, 1.4, 0), 25, 0.5, 0.6, 0.5, 0.02);
            }
            Player owner = Bukkit.getPlayer(m.owner);
            if (owner != null && (cs == null || cs.getBoolean("notify-streamer", true))) {
                owner.sendActionBar(Component.text(icon + msg.text(), col));
            }
        }
        if (cs != null && cs.getBoolean("broadcast", false)) {
            Bukkit.broadcast(Component.text(icon + msg.text(), col));
        }
    }

    /** A chat message containing a keyword (like "creeper") makes the mascot play that sound. */
    private void playChatSound(Mascot m, String text, long now) {
        ConfigurationSection cs = getConfig().getConfigurationSection("mascot.chat-sounds");
        if (cs == null || !cs.getBoolean("enabled", true) || m.body == null || !m.body.isValid()) return;
        long cooldown = (long) (cs.getDouble("cooldown-seconds", 3.0) * 1000L);
        if (now - m.lastSound < cooldown) return;
        ConfigurationSection map = cs.getConfigurationSection("sounds");
        if (map == null) return;
        for (String word : map.getKeys(false)) {
            String sound = map.getString(word);
            if (sound == null || sound.isBlank()) continue;
            if (!Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word.toLowerCase(Locale.ROOT))
                    + "(?![\\p{L}\\p{N}])").matcher(text.toLowerCase(Locale.ROOT)).find()) continue;
            m.lastSound = now;
            float vol = (float) cs.getDouble("volume", 1.0);
            float pitch = (float) cs.getDouble("pitch", 1.0);
            m.body.getWorld().playSound(m.body.getLocation(), sound.trim(),
                    org.bukkit.SoundCategory.NEUTRAL, sfx("chat", vol), pitch);
            return;
        }
    }

    /** Cleans a viewer's message. Returns null if it should be ignored. */
    private String cleanMessage(String raw, String user) {
        if (raw == null) return null;
        String text = raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return null;
        if (getConfig().getBoolean("mascot.ignore-commands", true) && text.startsWith("!")) return null;
        for (String ignored : getConfig().getStringList("mascot.ignored-users")) {
            if (ignored.equalsIgnoreCase(user)) return null;
        }
        for (String bad : getConfig().getStringList("mascot.blocked-words")) {
            if (bad == null || bad.isBlank()) continue;
            text = Pattern.compile(Pattern.quote(bad), Pattern.CASE_INSENSITIVE)
                    .matcher(text).replaceAll("***");
        }
        int max = Math.max(10, getConfig().getInt("mascot.max-message-length", 70));
        if (text.length() > max) text = text.substring(0, max - 1) + "...";
        return text;
    }

    /** One chat line: coloured name, grey colon, white text (no background). */
    private Component bubbleLine(String name, String text, int rgb) {
        return Component.empty()
                .append(Component.text(name, TextColor.color(rgb), TextDecoration.BOLD))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(text, NamedTextColor.WHITE));
    }

    private void addLine(Mascot m, Component line, long expiresAt, int max) {
        m.lines.addLast(new Mascot.Line(line, expiresAt));
        while (m.lines.size() > max) m.lines.pollFirst();
        m.dirty = true;
    }

    private void renderBoard(Mascot m, long now) {
        if (m.board == null) return;
        if (m.lines.removeIf(l -> l.expiresAt() < now)) m.dirty = true;
        if (!m.dirty) return;
        m.dirty = false;

        if (m.lines.isEmpty()) {
            m.board.text(Component.empty());
            m.board.setViewRange(0f); // hide the empty board
            return;
        }
        List<Component> parts = new ArrayList<>();
        for (Mascot.Line l : m.lines) parts.add(l.text());
        m.board.text(Component.join(JoinConfiguration.newlines(), parts));
        m.board.setViewRange(m.fade < 0.12f ? 0f : 1f);
    }

    private Location mascotLocation(Player p) {
        Location l = p.getLocation();
        double yaw = Math.toRadians(l.getYaw());
        double side = getConfig().getDouble("mascot.side-offset", 1.6);
        double back = 0.5;
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double rx = -Math.cos(yaw);
        double rz = -Math.sin(yaw);
        Location out = l.clone().add(rx * side - fx * back, 0, rz * side - fz * back);
        out.setPitch(0f);
        return out;
    }

    /** Hurts the mascot without an event (fall damage). Kills it if its health runs out. */
    private void hurtMascot(Mascot m, double dmg, String why) {
        if (m.baby) {
            hurtBaby(m, dmg);
            return;
        }
        if (dmg <= 0 || m.isGrave || m.body == null || !getConfig().getBoolean("mascot.can-be-killed", true)) return;
        UUID id = m.owner;
        double left = hp(id) - dmg;
        m.lastDamage = System.currentTimeMillis();
        m.nameDirty = true;
        dataDirty = true;
        m.body.playHurtAnimation(0f);
        m.body.getWorld().playSound(m.body.getLocation(), soundName("hit", "entity.copper_golem.hurt"), SoundCategory.PLAYERS, sfx("hurt", 1f), 1f);
        if (left <= 0) killMascot(m, null, why);
        else hpMap.put(id, left);
    }

    /** The ground level below a point (or the point itself if there is nothing within 64 blocks). */
    private Location groundAt(Location at) {
        World w = at.getWorld();
        Location out = at.clone();
        if (w == null) return out;
        RayTraceResult r = w.rayTraceBlocks(at.clone().add(0, 1.0, 0), new Vector(0, -1, 0), 64.0,
                FluidCollisionMode.NEVER, true);
        if (r != null) out.setY(r.getHitPosition().getY());
        out.setPitch(0f);
        return out;
    }

    private static boolean isClimbable(Block b) {
        return Tag.CLIMBABLE.isTagged(b.getType());
    }

    private static boolean isWater(Block b) {
        Material t = b.getType();
        if (t == Material.WATER || t == Material.BUBBLE_COLUMN || t == Material.KELP || t == Material.KELP_PLANT
                || t == Material.SEAGRASS || t == Material.TALL_SEAGRASS) {
            return true;
        }
        return b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged();
    }

    /** Flowing water that falls down (a waterfall column). */
    private static boolean isFallingWater(Block b) {
        return b.getType() == Material.WATER && b.getBlockData() instanceof Levelled lv && lv.getLevel() >= 8;
    }

    /** The nearest ladder, vine or waterfall within 2 blocks (centre of its cell), or null. */
    private Location findLadder(World w, Location at) {
        int bx = at.getBlockX();
        int by = (int) Math.floor(at.getY() + 0.1);
        int bz = at.getBlockZ();
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 0; y <= 1; y++) {
                    Block cand = w.getBlockAt(bx + x, by + y, bz + z);
                    if (!isClimbable(cand) && !isFallingWater(cand)) continue;
                    double d = x * x + z * z;
                    if (d < bestD) {
                        bestD = d;
                        best = new Location(w, bx + x + 0.5, by + y, bz + z + 0.5);
                    }
                }
            }
        }
        return best;
    }

    /**
     * Every tick: the mascot walks to its goal, swims, climbs ladders and vines, falls with gravity
     * and takes fall damage.
     */
    private void walkTick() {
        double boardHeight = boardHeight();
        double tpDist = getConfig().getDouble("mascot.teleport-distance", 12.0); // like a dog: 12 blocks
        for (Mascot m : allMascots()) {
            fadeTick(m);
            Location goal = m.walkGoal;
            if (goal == null || m.isGrave || m.body == null || !m.body.isValid() || m.body.isInsideVehicle()) continue;
            Location cur = m.body.getLocation();
            World w = cur.getWorld();
            if (w == null) continue;

            boolean follow = m.stopDist > 1.0;
            double dx = goal.getX() - cur.getX();
            double dz = goal.getZ() - cur.getZ();
            double dist = Math.hypot(dx, dz);
            double dy = goal.getY() - cur.getY();

            int bx = cur.getBlockX();
            int bz = cur.getBlockZ();
            int fy = (int) Math.floor(cur.getY() + 0.1);
            boolean onLadder = isClimbable(w.getBlockAt(bx, fy, bz));
            boolean inWater = isWater(w.getBlockAt(bx, (int) Math.floor(cur.getY() + 0.3), bz));
            boolean headWater = isWater(w.getBlockAt(bx, (int) Math.floor(cur.getY() + 1.0), bz));

            // a ladder can get it up a tall wall instead of teleporting
            Location ladder = null;
            if (follow && !onLadder && dy > 1.8 && dist <= tpDist) ladder = findLadder(w, cur);
            boolean climbTrip = follow && dist <= tpDist && dy > 3.5 && dy <= 40 && (onLadder || inWater || ladder != null)
                    && m.seekTicks < 600;
            if (dy > 1.8) m.seekTicks++; else m.seekTicks = 0;

            Location next;
            boolean snapped = false;
            boolean otherWorld = goal.getWorld() != w;
            // far above or below: it first tries to come on its own (slopes, stairs, ladders, waterfalls)
            // and only teleports if that takes too long, or the height difference is huge
            boolean farY = follow && !climbTrip && Math.abs(dy) > 3.5;
            if (farY) m.farYTicks++; else m.farYTicks = 0;
            double maxDy = getConfig().getDouble("mascot.teleport-height", 12.0);
            int waitTicks = (int) (getConfig().getDouble("mascot.teleport-wait-seconds", 8.0) * 20);
            boolean needTp = otherWorld
                    || (follow && !climbTrip && (dist > tpDist || Math.abs(dy) > maxDy))
                    || (farY && m.farYTicks > waitTicks)
                    || m.stuck > 40;
            double tpDelay = getConfig().getDouble("mascot.teleport-delay-seconds", 3.0);
            if (needTp && !otherWorld && tpDelay > 0) {
                long nowMs = System.currentTimeMillis();
                if (m.tpAt == 0) { // too far away: it gets ready to teleport, with a short delay
                    m.tpAt = nowMs + (long) (tpDelay * 1000L);
                }
                if (nowMs < m.tpAt) {
                    w.spawnParticle(Particle.PORTAL, cur.clone().add(0, 0.9, 0), 6, 0.3, 0.6, 0.3, 0.4);
                    continue; // charging up, standing still
                }
                needTp = true; // the delay is over: teleport now
            } else if (!needTp) {
                m.tpAt = 0;
            }
            if (needTp) {
                if (!otherWorld) {
                    w.spawnParticle(Particle.PORTAL, cur.clone().add(0, 1.0, 0), 40, 0.3, 0.7, 0.3, 0.6);
                }
                next = follow || otherWorld ? groundAt(goal) : goal.clone(); // on the ground next to the goal
                next.setPitch(0f);
                m.stuck = 0;
                m.vy = 0;
                m.fallDist = 0;
                m.seekTicks = 0;
                m.tpAt = 0;
                m.farYTicks = 0;
                snapped = true;
                if (next.getWorld() != null) {
                    next.getWorld().spawnParticle(Particle.PORTAL, next.clone().add(0, 1.0, 0), 40, 0.3, 0.7, 0.3, 0.6);
                }
            } else {
                next = cur.clone();
                next.setPitch(0f);
                if (dist > 0.05) next.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                tiltHead(m, next, dist);

                // --- horizontal movement
                double mx = 0;
                double mz = 0;
                double speed = inWater ? 0.14 : (dist > 8 ? 0.42 : 0.26); // slower when swimming, runs when far behind
                if (ladder != null) {
                    double lx = ladder.getX() - cur.getX();
                    double lz = ladder.getZ() - cur.getZ();
                    double ld = Math.hypot(lx, lz);
                    if (ld > 0.12) {
                        double st = Math.min(ld, 0.2);
                        mx = lx / ld * st;
                        mz = lz / ld * st;
                    }
                } else if (dist > m.stopDist) {
                    double st = Math.min(dist - m.stopDist + 0.05, speed);
                    mx = dx / dist * st;
                    mz = dz / dist * st;
                }

                // --- vertical movement
                double newY = cur.getY();
                boolean special = false;
                if (onLadder) {
                    special = true;
                    m.vy = 0;
                    m.fallDist = 0;
                    if (dy > 0.6) {
                        newY += 0.12; // climb up
                        mx = 0;
                        mz = 0;
                        if (!isClimbable(w.getBlockAt(bx, fy + 1, bz)) && dist > 0.05) { // top of the ladder: step off
                            mx = dx / dist * 0.2;
                            mz = dz / dist * 0.2;
                        }
                    } else if (dy < -0.6) {
                        newY -= 0.12; // climb down
                        mx = 0;
                        mz = 0;
                    } else if (dist > 0.05) { // same height: leave the ladder toward the goal
                        mx = dx / dist * 0.2;
                        mz = dz / dist * 0.2;
                    }
                } else if (inWater) {
                    special = true;
                    m.vy = 0;
                    m.fallDist = 0;
                    if (dy < -1.5) {
                        newY -= 0.08; // dive after someone who is deeper
                    } else if (headWater) {
                        newY += dy > 1.0 ? 0.16 : 0.1; // float up to the surface, or swim up a waterfall faster
                    }
                    if (dy > 1.8 && isFallingWater(w.getBlockAt(bx, (int) Math.floor(cur.getY() + 0.3), bz))) {
                        mx = 0; // stay in the waterfall while swimming up
                        mz = 0;
                    }
                }
                boolean partying = System.currentTimeMillis() < m.partyUntil;
                if (partying) { // celebrating: stay on the spot
                    mx = 0;
                    mz = 0;
                }

                // --- pushing: players walking into it, and knockback from hits
                boolean pushed = false;
                if (getConfig().getBoolean("mascot.pushable", true)) {
                    for (Player pl : w.getNearbyPlayers(cur, 0.9)) {
                        if (pl.getGameMode() == GameMode.SPECTATOR || Math.abs(pl.getLocation().getY() - cur.getY()) > 1.8) continue;
                        double px = cur.getX() - pl.getLocation().getX();
                        double pz = cur.getZ() - pl.getLocation().getZ();
                        double pd = Math.hypot(px, pz);
                        if (pd >= 0.85) continue;
                        if (pd < 0.01) {
                            double ang = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                            px = Math.cos(ang);
                            pz = Math.sin(ang);
                            pd = 1.0;
                        }
                        double push = Math.min(0.15, (0.85 - pd) * 0.5);
                        mx += px / pd * push;
                        mz += pz / pd * push;
                        pushed = true;
                    }
                    if (Math.abs(m.kx) > 0.01 || Math.abs(m.kz) > 0.01) {
                        mx += m.kx;
                        mz += m.kz;
                        m.kx *= 0.7;
                        m.kz *= 0.7;
                        pushed = true;
                    } else {
                        m.kx = 0;
                        m.kz = 0;
                    }
                }
                next.add(mx, 0, mz);

                // --- ground below the new spot
                double reach = 1.4 + Math.max(0.0, -m.vy);
                RayTraceResult r = w.rayTraceBlocks(next.clone().add(0, 1.3, 0), new Vector(0, -1, 0), reach,
                        FluidCollisionMode.NEVER, true);
                if (r != null && r.getHitPosition().getY() - cur.getY() > 1.1 && (mx != 0 || mz != 0)) {
                    m.stuck++; // a wall in the way: stay put, and catch up if it stays stuck
                    next.setX(cur.getX());
                    next.setZ(cur.getZ());
                    r = w.rayTraceBlocks(next.clone().add(0, 1.3, 0), new Vector(0, -1, 0), reach,
                            FluidCollisionMode.NEVER, true);
                } else if (mx != 0 || mz != 0) {
                    m.stuck = 0;
                }
                if (follow && !special && m.vy == 0 && (mx != 0 || mz != 0) && r == null) {
                    // a cliff edge: it only steps off if the drop is small, so it does not fall to its death
                    RayTraceResult far = w.rayTraceBlocks(next.clone().add(0, 1.3, 0), new Vector(0, -1, 0), 4.6,
                            FluidCollisionMode.NEVER, true);
                    if (far == null) {
                        next.setX(cur.getX());
                        next.setZ(cur.getZ());
                        r = w.rayTraceBlocks(next.clone().add(0, 1.3, 0), new Vector(0, -1, 0), reach,
                                FluidCollisionMode.NEVER, true);
                    }
                }
                double groundY = r != null ? r.getHitPosition().getY() : Double.NaN;
                if (pushed && !follow) { // a mascot that stays keeps the spot it was pushed to
                    Location home = next.clone();
                    m.walkGoal = home;
                    homes.put(m.owner, Home.of(home));
                    dataDirty = true;
                }

                if ((partying || System.currentTimeMillis() < m.hopUntil) && !special && m.vy == 0 && !Double.isNaN(groundY) && cur.getY() - groundY < 0.06) {
                    m.vy = 0.42; // hop
                }
                if (special) {
                    if (!Double.isNaN(groundY) && newY < groundY) newY = groundY;
                    next.setY(newY);
                } else if (!Double.isNaN(groundY) && cur.getY() + m.vy <= groundY + 0.001) {
                    // on the ground (or landing on it)
                    double fallen = m.fallDist;
                    next.setY(groundY);
                    m.vy = 0;
                    m.fallDist = 0;
                    if (fallen > 3.0) hurtMascot(m, Math.floor(fallen - 3.0), "Fell from a high place");
                    if (!m.body.isValid()) continue; // it died from the fall
                } else {
                    m.vy = (m.vy - 0.08) * 0.98;
                    if (m.vy < -3.0) m.vy = -3.0;
                    next.setY(cur.getY() + m.vy);
                    if (m.vy < 0) m.fallDist += -m.vy;
                    if (next.getY() < w.getMinHeight() - 10) { // fell out of the world
                        next = groundAt(goal);
                        m.vy = 0;
                        m.fallDist = 0;
                        snapped = true;
                    }
                }
                if (!snapped && next.distanceSquared(cur) < 1.0E-6 && Math.abs(next.getYaw() - cur.getYaw()) < 2f
                        && Math.abs(next.getPitch() - cur.getPitch()) < 2f) continue;
            }
            stepSounds(m, cur, next);
            m.body.teleport(next);
            if (m.board != null && m.board.isValid()) m.board.teleport(next.clone().add(0, boardHeight * m.sizeFactor, 0));
            if (m.leashProxy != null && m.leashProxy.isValid()) m.leashProxy.teleport(next.clone().add(0, 0.8, 0));
        }
    }

    /** Eye contact: a mascot that is standing still and being looked at turns to the player and cocks its head. */
    private void tiltHead(Mascot m, Location next, double dist) {
        if (m.isGrave || m.body == null || next.getWorld() == null
                || !getConfig().getBoolean("mascot.eye-contact-tilt", true) || dist > m.stopDist + 0.3) {
            m.contactSince = 0;
            return;
        }
        double eyeH = 1.6 * mascotScale() * m.sizeFactor;
        Location eye = next.clone().add(0, eyeH, 0);
        Player watcher = null;
        for (Player pl : next.getWorld().getPlayers()) {
            if (pl.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            Location pe = pl.getEyeLocation();
            Vector to = eye.toVector().subtract(pe.toVector());
            double d = to.length();
            if (d > 8.0 || d < 0.5) continue;
            if (pe.getDirection().dot(to.normalize()) < 0.985) continue; // about 10 degrees
            if (!pl.hasLineOfSight(m.body)) continue;
            watcher = pl;
            break;
        }
        if (watcher == null) {
            m.contactSince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (m.contactSince == 0) m.contactSince = now;
        Vector look = watcher.getEyeLocation().toVector().subtract(eye.toVector());
        float yaw = (float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ()));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.getY(), Math.hypot(look.getX(), look.getZ())));
        if (now - m.contactSince > 400) { // after a moment it cocks its head to the side
            yaw += 22f;
            pitch += 14f;
        }
        next.setYaw(yaw);
        next.setPitch(pitch);
    }

    /** Below a few hearts the mascot goes berserk and attacks the nearest player - its owner included. */
    private void berserk(Mascot m, UUID id, long now) {
        double hearts = getConfig().getDouble("mascot.berserk-below-hearts", 4.0);
        if (hearts <= 0 || hp(id) >= hearts * 2.0 || m.isGrave || m.body == null) {
            m.berserk = false;
            return;
        }
        double range = getConfig().getDouble("mascot.berserk-range", 16.0);
        boolean hitOwner = getConfig().getBoolean("mascot.berserk-attacks-owner", false);
        Player target = null;
        double best = range * range;
        for (Player pl : m.body.getWorld().getPlayers()) {
            if (pl.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (!hitOwner && pl.getUniqueId().equals(id)) continue; // spares its owner
            double d2 = pl.getLocation().distanceSquared(m.body.getLocation());
            if (d2 < best) {
                best = d2;
                target = pl;
            }
        }
        if (!m.berserk) {
            m.berserk = true;
        }
        if (target == null) return;
        m.walkGoal = target.getLocation();
        m.stopDist = 1.0;
        Location head = m.body.getLocation().add(0, 1.5 * mascotScale() + 0.3, 0);
        m.body.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, head, 1, 0.15, 0.1, 0.15, 0);
        long cooldown = (long) (getConfig().getDouble("mascot.berserk-cooldown-seconds", 1.0) * 1000L);
        if (Math.sqrt(best) <= 2.2 && now - m.lastAttack >= cooldown) {
            m.lastAttack = now;
            m.body.swingMainHand();
            target.damage(getConfig().getDouble("mascot.berserk-damage", 2.0), m.body);
        }
    }

    private String soundName(String key, String def) {
        return getConfig().getString("mascot.sound-names." + key, def);
    }

    /** Copper-golem footsteps while walking, on jumping and landing, and now and then an idle sound. */
    private void stepSounds(Mascot m, Location cur, Location next) {
        World w = next.getWorld();
        if (w == null || m.isGrave) return;
        double dx = next.getX() - cur.getX();
        double dz = next.getZ() - cur.getZ();
        double dy = next.getY() - cur.getY();
        boolean air = Math.abs(dy) > 0.03;
        float pitchBase = m.baby ? 1.4f : 1.0f;
        float stepVol = sfx("steps", 0.6f);
        String step = soundName("step", "entity.copper_golem.step");
        if (stepVol > 0f) {
            if (air && !m.airborne) {
                w.playSound(next, step, SoundCategory.NEUTRAL, stepVol, pitchBase * 1.25f); // jump
            } else if (!air && m.airborne) {
                w.playSound(next, step, SoundCategory.NEUTRAL, stepVol, pitchBase * 0.8f); // landing
            }
            if (!air) {
                m.stride += Math.hypot(dx, dz);
                if (m.stride >= 1.3) {
                    m.stride = 0;
                    w.playSound(next, step, SoundCategory.NEUTRAL, stepVol,
                            pitchBase * (0.95f + ThreadLocalRandom.current().nextFloat() * 0.1f));
                }
            } else {
                m.stride = 0;
            }
        }
        m.airborne = air;
        // idle sound while standing still
        long nowMs = System.currentTimeMillis();
        if (!air && Math.hypot(dx, dz) < 0.01) {
            if (m.nextIdle == 0) {
                m.nextIdle = nowMs + 20_000L + ThreadLocalRandom.current().nextLong(40_000L);
            } else if (nowMs >= m.nextIdle) {
                m.nextIdle = nowMs + 25_000L + ThreadLocalRandom.current().nextLong(50_000L);
                float iv = sfx("idle", 0.7f);
                if (iv > 0f) w.playSound(next, soundName("idle", "entity.copper_golem.spin"), SoundCategory.NEUTRAL, iv, pitchBase);
            }
        }
    }

    /** Fades the name and the chat out while the owner crouches for a while, and back in afterwards. */
    private void fadeTick(Mascot m) {
        float target = m.wantHidden || System.currentTimeMillis() < m.introHideUntil ? 0f : 1f;
        if (m.fade == target || m.isGrave) return;
        if (m.introHideUntil != 0 && m.fade > target && System.currentTimeMillis() < m.introHideUntil) m.fade = 0f; // entrance: hidden at once
        m.fade = m.fade < target ? Math.min(target, m.fade + 0.05f) : Math.max(target, m.fade - 0.05f);
        if (m.board != null && m.board.isValid()) {
            m.board.setTextOpacity((byte) Math.max(40, Math.round(m.fade * 255f)));
            if (m.fade < 0.12f) m.board.setViewRange(0f);
            else if (!m.lines.isEmpty()) m.board.setViewRange(1f);
        }
        boolean showName = m.fade >= 0.5f;
        if (showName != m.nameShown && m.body != null && m.body.isValid()) {
            m.nameShown = showName;
            m.body.setCustomNameVisible(showName);
            if (showName) {
                m.nameDirty = true;
                if (m.baby) m.body.setDescription(Component.text("baby", NamedTextColor.GRAY));
            } else {
                m.body.setDescription(null);
            }
        }
    }

    private void moveMascot(Mascot m, Location target) {
        double boardHeight = boardHeight();
        if (m.body.isInsideVehicle()) { // sitting in a boat: only the chat board and lead follow
            Location at = m.body.getLocation();
            m.board.teleport(at.clone().add(0, boardHeight, 0));
            if (m.leashProxy != null && m.leashProxy.isValid()) m.leashProxy.teleport(at.clone().add(0, 0.8, 0));
            return;
        }
        Location cur = m.body.getLocation();
        if (cur.getWorld() == target.getWorld()
                && cur.distanceSquared(target) < 0.0004
                && Math.abs(cur.getYaw() - target.getYaw()) < 1f) {
            return;
        }
        m.body.teleport(target);
        m.board.teleport(target.clone().add(0, boardHeight, 0));
        if (m.leashProxy != null && m.leashProxy.isValid()) m.leashProxy.teleport(target.clone().add(0, 0.8, 0));
    }

    /** Short name tag: just the streamer's name (with a red dot while they are live). */
    private Component mascotName(UUID id, int rgb, boolean live) {
        String name = null;
        String login = links.get(id);
        if (login != null && !"minecraft".equalsIgnoreCase(getConfig().getString("mascot.name-source", "twitch"))) {
            name = twitchNames.getOrDefault(login.toLowerCase(Locale.ROOT), login); // the Twitch channel name
        }
        if (name == null) name = Bukkit.getOfflinePlayer(id).getName();
        if (name == null) name = "Streamer";
        Component n = Component.text(name, TextColor.color(rgb));
        return live ? Component.text("\u25CF ", NamedTextColor.RED).append(n) : n;
    }

    /** Hearts under the name, like a player's health: 10 hearts, a half heart for odd points. */
    private Component healthBar(UUID id) {
        double cur = hp(id);
        double max = maxHp();
        boolean always = getConfig().getBoolean("mascot.always-show-health", false);
        if (!always && cur >= max - 0.01) return null;
        int hearts = 10;
        double perHeart = max / hearts;
        Component out = Component.empty();
        for (int i = 0; i < hearts; i++) {
            double inHeart = cur - i * perHeart;
            net.kyori.adventure.text.format.TextColor col;
            if (inHeart >= perHeart - 0.01) col = NamedTextColor.RED;                 // full heart
            else if (inHeart >= perHeart / 2 - 0.01) col = TextColor.color(0x992222); // half heart
            else col = NamedTextColor.DARK_GRAY;                                      // empty
            out = out.append(Component.text("\u2764", col));
        }
        return out;
    }

    private void updateNameplate(Mannequin e, UUID id, int rgb, boolean live) {
        e.customName(mascotName(id, rgb, live));
        e.setDescription(healthBar(id));
    }

    /**
     * The mascot's big entrance on its streamer's very first stream: a beam of light falls from the sky,
     * bursts on the ground, and the mascot grows out of the flash.
     */
    private void playIntro(Mascot m) {
        if (!getConfig().getBoolean("mascot.intro.enabled", true) || m.body == null || !m.body.isValid()) return;
        final Location ground = m.body.getLocation().clone();
        final World w = ground.getWorld();
        if (w == null) return;
        final int charge = 200; // ticks of build-up (10 s)
        final int beam = charge + 20; // the beam falls (1 s)
        final int grow = beam + 22; // it grows out of the flash
        final double top = ground.getY() + 14.0;
        final double fullScale = mascotScale();
        m.introHideUntil = System.currentTimeMillis() + beam * 50L + 100L;
        AttributeInstance sc0 = m.body.getAttribute(Attribute.SCALE);
        if (sc0 != null) sc0.setBaseValue(0.0625);
        if (getConfig().getBoolean("mascot.intro.announce", false)) {
            Bukkit.broadcast(Component.text(streamerName(m.owner) + "'s chat mascot is arriving...",
                    TextColor.color(m.rgb)));
        }
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (m.body == null || !m.body.isValid()) {
                    cancel();
                    return;
                }
                Location at = m.body.getLocation();
                if (t < charge) { // build-up: light spirals in and rises, with chimes that climb in pitch
                    double p = t / (double) charge;
                    double radius = 2.6 - 2.0 * p;
                    for (int arm = 0; arm < 3; arm++) {
                        double a = t * 0.35 + arm * Math.PI * 2 / 3;
                        double y = at.getY() + 0.1 + 2.2 * ((t * 0.04 + arm * 0.33) % 1.0);
                        w.spawnParticle(Particle.END_ROD, at.getX() + Math.cos(a) * radius, y,
                                at.getZ() + Math.sin(a) * radius, 1, 0, 0, 0, 0);
                    }
                    if (t % 5 == 0) { // a ring on the ground that tightens
                        for (int i = 0; i < 16; i++) {
                            double a = Math.PI * 2 * i / 16.0;
                            w.spawnParticle(Particle.END_ROD, at.getX() + Math.cos(a) * (radius + 0.4),
                                    at.getY() + 0.05, at.getZ() + Math.sin(a) * (radius + 0.4), 0, 0, 0.02, 0, 0.0);
                        }
                    }
                    if (t % 12 == 0) {
                        w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.NEUTRAL,
                                sfx("intro", 0.7f), (float) (0.6 + 1.0 * p));
                    }
                } else if (t < beam) { // the beam falls
                    double p = (t - charge) / (double) (beam - charge - 1);
                    double y = top - (top - ground.getY()) * p;
                    w.spawnParticle(Particle.END_ROD, at.getX(), y, at.getZ(), 10, 0.07, 0.5, 0.07, 0.0);
                    w.spawnParticle(Particle.END_ROD, at.getX(), y + 2, at.getZ(), 4, 0.04, 1.0, 0.04, 0.0);
                    if (t == charge) {
                        w.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.NEUTRAL, sfx("intro", 0.5f), 1.6f);
                    }
                } else if (t == beam) { // the burst
                    for (int i = 0; i < 32; i++) {
                        double a = Math.PI * 2 * i / 32.0;
                        w.spawnParticle(Particle.END_ROD, at.getX(), at.getY() + 0.2, at.getZ(), 0,
                                Math.cos(a), 0.05, Math.sin(a), 0.3);
                    }
                    w.spawnParticle(Particle.FIREWORK, at.clone().add(0, 0.8, 0), 70, 0.3, 0.6, 0.3, 0.14);
                    w.spawnParticle(Particle.TOTEM_OF_UNDYING, at.clone().add(0, 0.8, 0), 35, 0.3, 0.6, 0.3, 0.3);
                    w.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.NEUTRAL, sfx("intro", 0.8f), 1.4f);
                    w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.NEUTRAL, sfx("intro", 1f), 1.2f);
                } else if (t <= grow) { // it grows out of the flash, with a little spiral
                    double f = Math.min(1.0, (t - beam) / (double) (grow - beam));
                    f = 1 - Math.pow(1 - f, 3); // eases out
                    AttributeInstance sc = m.body.getAttribute(Attribute.SCALE);
                    if (sc != null) sc.setBaseValue(Math.max(0.0625, fullScale * f));
                    double a = (t - beam) * 0.9;
                    for (int k = 0; k < 2; k++) {
                        double aa = a + k * Math.PI;
                        w.spawnParticle(Particle.END_ROD, at.getX() + Math.cos(aa) * 0.6,
                                at.getY() + (t - beam) * 0.09, at.getZ() + Math.sin(aa) * 0.6, 1, 0, 0, 0, 0);
                    }
                } else {
                    AttributeInstance sc = m.body.getAttribute(Attribute.SCALE);
                    if (sc != null) sc.setBaseValue(fullScale);
                    cancel();
                    return;
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private Mascot createMascot(UUID id, Location base) {
        int rgb = colorFor(id);
        Mascot m = new Mascot(id, links.get(id), rgb);
        World w = base.getWorld();
        if (w == null) return null;
        double boardHeight = boardHeight();

        final boolean[] skinned = {false};
        try {
            m.body = w.spawn(base, Mannequin.class, e -> {
                e.setPersistent(false);
                e.setSilent(true);
                e.setGravity(false);
                e.setCollidable(false);
                e.setImmovable(true);
                e.setInvulnerable(false); // mannequins are invulnerable by default; the plugin handles damage itself
                AttributeInstance sc = e.getAttribute(Attribute.SCALE);
                if (sc != null) sc.setBaseValue(mascotScale());
                e.addScoreboardTag(MASCOT_TAG);
                updateNameplate(e, id, rgb, false);
                e.setCustomNameVisible(true);
                skinned[0] = applySkin(e, id);
                dressMascot(e, rgb, id, !skinned[0]);
            });
            m.leather = !skinned[0];
            m.board = w.spawn(base.clone().add(0, boardHeight, 0), TextDisplay.class, t -> {
                t.setPersistent(false);
                t.addScoreboardTag(MASCOT_TAG);
                t.setBillboard(Display.Billboard.CENTER);
                t.setAlignment(TextDisplay.TextAlignment.CENTER);
                t.setShadowed(true);
                t.setLineWidth(200);
                t.setTeleportDuration(3);
                t.setDefaultBackground(false);
                t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0)); // no background box
                float bs = (float) Math.max(0.2, getConfig().getDouble("mascot.board-scale", 0.6));
                t.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(),
                        new Vector3f(bs, bs, bs), new AxisAngle4f()));
                t.text(Component.empty());
                t.setViewRange(0f);
            });
        } catch (Exception ex) {
            if (!warnedSpawn) {
                warnedSpawn = true;
                getLogger().warning("Could not spawn a chat mascot: " + ex);
                ex.printStackTrace();
                for (Player adm : Bukkit.getOnlinePlayers()) {
                    if (adm.hasPermission("livetag.admin")) {
                        adm.sendMessage(Component.text("[ChatMate] Mascot spawn failed: " + ex, NamedTextColor.RED));
                    }
                }
            }
            m.remove();
            return null;
        }
        return m;
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /**
     * Gives the mascot its skin. Each streamer's colour has its own coloured TV skin
     * (mascot.skins, same order as mascot.colors). Returns true if that coloured skin was used;
     * otherwise the single fallback skin, or the default look, is used.
     */
    private boolean applySkin(Mannequin e, UUID id) {
        int[] pal = palette();
        int idx = Math.floorMod(colorIndex.getOrDefault(id, 0), pal.length);
        String value = "";
        String sig = "";
        boolean colored = false;

        List<Map<?, ?>> list = getConfig().getMapList("mascot.skins");
        if (idx < list.size()) {
            Map<?, ?> entry = list.get(idx);
            value = text(entry.get("value"));
            sig = text(entry.get("signature"));
            colored = !value.isEmpty();
        }
        if (value.isEmpty()) {
            value = getConfig().getString("mascot.skin-texture-value", "").trim();
            sig = getConfig().getString("mascot.skin-texture-signature", "").trim();
        }
        String name = getConfig().getString("mascot.skin-name", "").trim();
        try {
            if (!value.isEmpty()) {
                // a different profile id per colour so clients never mix the skins up
                UUID profileId = UUID.nameUUIDFromBytes(
                        ("livetag-mascot-" + (colored ? idx : -1)).getBytes(StandardCharsets.UTF_8));
                ResolvableProfile profile = ResolvableProfile.resolvableProfile()
                        .name("TVMascot")
                        .uuid(profileId)
                        .addProperty(new ProfileProperty("textures", value, sig.isEmpty() ? null : sig))
                        .build();
                e.setProfile(profile);
            } else if (!name.isEmpty()) {
                e.setProfile(ResolvableProfile.resolvableProfile().name(name).build());
            }
        } catch (Exception ex) {
            getLogger().warning("Could not apply the mascot skin: " + ex.getMessage());
            return false;
        }
        return colored;
    }

    /**
     * Each streamer's colour has its own skin. Without one, the default outfit is leather armour in the colour;
     * anything the streamer has put on the mascot themselves replaces that piece.
     */
    private void dressMascot(Mannequin e, int rgb, UUID id, boolean leather) {
        EntityEquipment eq = e.getEquipment();
        if (eq == null) return;
        Color color = Color.fromRGB(rgb);
        Map<EquipmentSlot, ItemStack> mine = customArmor.get(id);
        eq.setHelmet(pick(mine, EquipmentSlot.HEAD, null));
        eq.setChestplate(pick(mine, EquipmentSlot.CHEST, dyed(Material.LEATHER_CHESTPLATE, color))); // coloured chestplate only
        eq.setLeggings(pick(mine, EquipmentSlot.LEGS, null));
        eq.setBoots(pick(mine, EquipmentSlot.FEET, null));
        ItemStack held = pick(mine, EquipmentSlot.HAND, null); // the one item it holds
        eq.setItemInMainHand(held == null ? new ItemStack(Material.AIR) : held);
        // The streamer keeps their own armour, so nothing should drop if the mascot is removed.
        try {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                eq.setDropChance(slot, 0f);
            }
        } catch (Exception ignored) {
        }
    }

    private ItemStack pick(Map<EquipmentSlot, ItemStack> mine, EquipmentSlot slot, ItemStack fallback) {
        ItemStack custom = mine == null ? null : mine.get(slot);
        return custom != null ? custom.clone() : fallback;
    }

    private ItemStack dyed(Material mat, Color color) {
        ItemStack item = new ItemStack(mat);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(color);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------ breeding

    private String streamerName(UUID id) {
        String login = links.get(id);
        String name = null;
        if (login != null && !"minecraft".equalsIgnoreCase(getConfig().getString("mascot.name-source", "twitch"))) {
            name = twitchNames.getOrDefault(login.toLowerCase(Locale.ROOT), login);
        }
        if (name == null) name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? "Streamer" : name;
    }

    private boolean hasBaby(UUID streamer) {
        for (Baby b : babies.values()) {
            if (b.a().equals(streamer) || b.b().equals(streamer)) return true;
        }
        return false;
    }

    /** The owner feeds their mascot the breeding item: it falls in love for a while. */
    private void loveMascot(Player p, Mascot m, ItemStack hand) {
        if (hasBaby(m.owner)) {
            p.sendActionBar(Component.text("Your mascot already has a baby.", NamedTextColor.GRAY));
            return;
        }
        if (!links.containsKey(m.owner)) {
            p.sendActionBar(Component.text("Only streamers with a linked Twitch can breed.", NamedTextColor.RED));
            return;
        }
        takeOne(p, hand);
        long secs = getConfig().getLong("mascot.breeding.love-seconds", 30L);
        inLove.put(m.owner, System.currentTimeMillis() + secs * 1000L);
        Location at = m.body.getLocation().add(0, 1.2, 0);
        at.getWorld().spawnParticle(Particle.HEART, at, 12, 0.4, 0.4, 0.4, 0.02);
        at.getWorld().playSound(at, Sound.ENTITY_PLAYER_BURP, SoundCategory.NEUTRAL, sfx("baby", 1f), 1.2f);
        p.sendActionBar(Component.text("Your mascot is in love! Bring it next to another mascot that is in love too.",
                NamedTextColor.LIGHT_PURPLE));
    }

    private void breed(Mascot x, Mascot y) {
        UUID id = UUID.randomUUID();
        babies.put(id, new Baby(id, x.owner, y.owner, System.currentTimeMillis()));
        inLove.remove(x.owner);
        inLove.remove(y.owner);
        Location mid = x.body.getLocation().add(y.body.getLocation()).multiply(0.5).add(0, 1.2, 0);
        World w = mid.getWorld();
        if (w != null) {
            w.spawnParticle(Particle.HEART, mid, 40, 0.8, 0.6, 0.8, 0.05);
            w.playSound(mid, Sound.ENTITY_VILLAGER_CELEBRATE, SoundCategory.NEUTRAL, sfx("baby", 1f), 1f);
        }
        dataDirty = true;
        if (getConfig().getBoolean("mascot.breeding.announce", true)) {
            Bukkit.broadcast(Component.text(streamerName(x.owner) + " and " + streamerName(y.owner)
                    + " had a baby mascot!", NamedTextColor.LIGHT_PURPLE));
        }
    }

    private Baby babyOf(Mascot m) {
        return m.babyId == null ? null : babies.get(m.babyId);
    }

    private void hurtBaby(Mascot m, double dmg) {
        if (dmg <= 0 || m.body == null || m.babyId == null) return;
        double max = getConfig().getDouble("mascot.breeding.baby-health", 10.0);
        double left = babyHp.getOrDefault(m.babyId, max) - dmg;
        m.body.playHurtAnimation(0f);
        m.body.getWorld().playSound(m.body.getLocation(), soundName("hit", "entity.copper_golem.hurt"), SoundCategory.PLAYERS, sfx("hurt", 1f), 1.5f);
        m.body.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, m.body.getLocation().add(0, 0.7, 0), 4, 0.2, 0.2, 0.2);
        if (left <= 0) killBaby(m);
        else babyHp.put(m.babyId, left);
    }

    /** A baby that dies is gone for good (no grave, no reviving). Its parents may have a new one. */
    private void killBaby(Mascot m) {
        Baby b = babyOf(m);
        if (m.body != null && m.body.isValid()) {
            Location at = m.body.getLocation().add(0, 0.6, 0);
            at.getWorld().spawnParticle(Particle.SMOKE, at, 25, 0.2, 0.3, 0.2, 0.02);
            at.getWorld().playSound(at, Sound.ENTITY_PLAYER_DEATH, SoundCategory.PLAYERS, sfx("baby", 1f), 1.6f);
        }
        if (m.babyId != null) {
            babyMascots.remove(m.babyId);
            babies.remove(m.babyId);
            babyHp.remove(m.babyId);
            babyChatOff.remove(m.babyId);
            babyStay.remove(m.babyId);
        }
        discard(m);
        dataDirty = true;
        if (b != null && getConfig().getBoolean("mascot.breeding.announce", true)) {
            Bukkit.broadcast(Component.text("The baby of " + streamerName(b.a()) + " and " + streamerName(b.b())
                    + " died.", NamedTextColor.GRAY));
        }
    }

    /** Right-click: stop / start following. Sneak + right-click: chat off / on. */
    private void babyClick(Player p, Mascot m) {
        Location at = m.body.getLocation().add(0, 0.8, 0);
        at.getWorld().spawnParticle(Particle.HEART, at, 3, 0.2, 0.2, 0.2, 0.01);
        Baby b = babyOf(m);
        if (b == null) return;
        if (p.isSneaking()) {
            m.chatOff = !m.chatOff;
            if (m.chatOff) {
                babyChatOff.add(b.id());
                m.lines.clear();
                m.dirty = true;
            } else {
                babyChatOff.remove(b.id());
            }
            p.sendActionBar(Component.text("Baby chat " + (m.chatOff ? "off" : "on"), NamedTextColor.LIGHT_PURPLE));
        } else {
            boolean nowStay = babyStay.add(b.id());
            if (!nowStay) babyStay.remove(b.id());
            p.sendActionBar(Component.text(nowStay ? "The baby stays here." : "The baby follows again.",
                    NamedTextColor.LIGHT_PURPLE));
        }
    }

    private Mascot createBaby(Baby b, Location base) {
        World w = base.getWorld();
        if (w == null) return null;
        int rgbA = colorFor(b.a());
        int rgbB = colorFor(b.b());
        int rgb = (((rgbA >> 16 & 0xFF) + (rgbB >> 16 & 0xFF)) / 2) << 16
                | (((rgbA >> 8 & 0xFF) + (rgbB >> 8 & 0xFF)) / 2) << 8
                | (((rgbA & 0xFF) + (rgbB & 0xFF)) / 2);
        double factor = Math.max(0.2, getConfig().getDouble("mascot.breeding.baby-scale", 0.5));
        Mascot m = new Mascot(b.a(), links.get(b.a()), rgb);
        m.baby = true;
        m.babyId = b.id();
        m.chatOff = babyChatOff.contains(b.id());
        m.login2 = links.get(b.b());
        m.sizeFactor = factor;
        m.stopDist = 2.0;
        try {
            m.body = w.spawn(base, Mannequin.class, e -> {
                e.setPersistent(false);
                e.setSilent(true);
                e.setGravity(false);
                e.setCollidable(false);
                e.setImmovable(true);
                e.setInvulnerable(false); // the plugin handles the baby's damage itself
                AttributeInstance sc = e.getAttribute(Attribute.SCALE);
                if (sc != null) sc.setBaseValue(mascotScale() * factor);
                e.addScoreboardTag(MASCOT_TAG);
                e.customName(Component.text(streamerName(b.a()) + " + " + streamerName(b.b()), TextColor.color(rgb)));
                e.setCustomNameVisible(true);
                e.setDescription(Component.text("baby", NamedTextColor.GRAY)); // replaces the default "NPC"
                applySkin(e, b.a()); // the skin of the first parent's colour
                EntityEquipment eq = e.getEquipment();
                if (eq != null) {
                    eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, Color.fromRGB(rgb)));
                }
            });
            m.board = w.spawn(base.clone().add(0, boardHeight() * factor, 0), TextDisplay.class, t -> {
                t.setPersistent(false);
                t.addScoreboardTag(MASCOT_TAG);
                t.setBillboard(Display.Billboard.CENTER);
                t.setAlignment(TextDisplay.TextAlignment.CENTER);
                t.setShadowed(true);
                t.setLineWidth(200);
                t.setTeleportDuration(3);
                t.setDefaultBackground(false);
                t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                float bs = (float) Math.max(0.2, getConfig().getDouble("mascot.board-scale", 0.6));
                t.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(),
                        new Vector3f(bs, bs, bs), new AxisAngle4f()));
                t.text(Component.empty());
                t.setViewRange(0f);
            });
        } catch (Exception ex) {
            getLogger().warning("Could not spawn a baby mascot: " + ex);
            for (Player adm : Bukkit.getOnlinePlayers()) {
                if (adm.hasPermission("livetag.admin")) {
                    adm.sendMessage(Component.text("[LiveTag] Baby spawn error: " + ex, NamedTextColor.RED));
                }
            }
            m.remove();
            return null;
        }
        return m;
    }

    /** Runs with the mascot tick: love hearts, breeding, and keeping every baby alive and near its parents. */
    private void tickBabies(long now, Set<String> channels, boolean offlineChat) {
        // mascots in love: hearts, and breeding when two are close to each other
        inLove.values().removeIf(until -> until < now);
        if (!inLove.isEmpty()) {
            double maxDist = getConfig().getDouble("mascot.breeding.max-distance", 4.5);
            List<Mascot> loving = new ArrayList<>();
            for (Mascot m : mascots.values()) {
                if (m.isGrave || !m.valid() || !links.containsKey(m.owner) || !inLove.containsKey(m.owner) || hasBaby(m.owner)) continue;
                loving.add(m);
                m.body.getWorld().spawnParticle(Particle.HEART, m.body.getLocation().add(0, 1.3, 0), 1, 0.3, 0.2, 0.3, 0);
            }
            outer:
            for (int i = 0; i < loving.size(); i++) {
                for (int j = i + 1; j < loving.size(); j++) {
                    Mascot x = loving.get(i);
                    Mascot y = loving.get(j);
                    if (x.body.getWorld() == y.body.getWorld()
                            && x.body.getLocation().distance(y.body.getLocation()) <= maxDist) {
                        breed(x, y);
                        break outer;
                    }
                }
            }
        }

        double days = getConfig().getDouble("mascot.breeding.baby-days", 7.0);
        Iterator<Baby> it = babies.values().iterator();
        while (it.hasNext()) {
            Baby b = it.next();
            boolean grownUp = days > 0 && now - b.born() > (long) (days * 86400_000L);
            boolean orphan = !links.containsKey(b.a()) || !links.containsKey(b.b());
            if (grownUp || orphan) {
                Mascot old = babyMascots.remove(b.id());
                if (old != null) {
                    if (grownUp && old.body != null && old.body.isValid()) {
                        Location at = old.body.getLocation().add(0, 0.8, 0);
                        at.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at, 40, 0.3, 0.5, 0.3, 0.2);
                        at.getWorld().playSound(at, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.NEUTRAL, sfx("baby", 1f), 1.3f);
                    }
                    discard(old);
                }
                it.remove();
                dataDirty = true;
                if (grownUp && getConfig().getBoolean("mascot.breeding.announce", true)) {
                    Bukkit.broadcast(Component.text("The baby of " + streamerName(b.a()) + " and "
                            + streamerName(b.b()) + " grew up and left to explore the world.", NamedTextColor.GRAY));
                }
                continue;
            }

            try {
                tickOneBaby(b, now, channels, offlineChat);
            } catch (Exception ex) {
                reportBabyError(ex);
            }
        }
    }

    private boolean babyErrorShown;

    private void reportBabyError(Exception ex) {
        getLogger().warning("Baby mascot error: " + ex);
        ex.printStackTrace();
        if (babyErrorShown) return;
        babyErrorShown = true;
        for (Player adm : Bukkit.getOnlinePlayers()) {
            if (adm.hasPermission("livetag.admin")) {
                adm.sendMessage(Component.text("[LiveTag] Baby error: " + ex, NamedTextColor.RED));
            }
        }
    }

    private void tickOneBaby(Baby b, long now, Set<String> channels, boolean offlineChat) {
        {
            Player pa = Bukkit.getPlayer(b.a());
            Player pb = Bukkit.getPlayer(b.b());
            Mascot m = babyMascots.get(b.id());
            if (pa == null && pb == null) { // both parents are offline: the baby waits
                if (m != null) {
                    discard(m);
                    babyMascots.remove(b.id());
                }
                return;
            }
            Player near = pa != null ? pa : pb;
            if (pa != null && pb != null) {
                Location ref = m != null && m.body != null && m.body.isValid() ? m.body.getLocation() : pa.getLocation();
                if (ref.getWorld() == pb.getWorld()
                        && (pa.getWorld() != ref.getWorld() || pb.getLocation().distanceSquared(ref) < pa.getLocation().distanceSquared(ref))) {
                    near = pb;
                }
            }
            if (m == null || !m.valid()) {
                if (m != null) discard(m);
                Location spawn = mascotLocation(near);
                if (!spawn.getWorld().isChunkLoaded(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4)) return;
                m = createBaby(b, spawn);
                if (m == null) return;
                babyMascots.put(b.id(), m);
            }
            m.login = links.get(b.a());
            m.login2 = links.get(b.b());
            if (near.isSneaking()) {
                if (m.sneakSince == 0) m.sneakSince = now;
            } else {
                m.sneakSince = 0;
            }
            double hideAfter = getConfig().getDouble("mascot.hide-after-crouch-seconds", 5.0);
            m.wantHidden = hideAfter > 0 && m.sneakSince != 0 && now - m.sneakSince >= (long) (hideAfter * 1000L);
            m.walkGoal = babyStay.contains(b.id()) ? null : near.getLocation();
            m.stopDist = 2.0;
            if (offlineChat || twitchLive.contains(b.a()) || twitchLive.contains(b.b())) {
                if (m.login != null) channels.add(m.login);
                if (m.login2 != null) channels.add(m.login2);
            }
            renderBoard(m, now);
        }
    }

    // ------------------------------------------------------------ lead and boats

    /**
     * Mannequins can't be leashed themselves, so the lead is tied to an invisible baby chicken
     * that stays glued to the mascot. While it is leashed to a player, the mascot follows THAT
     * player; tied to a fence it stays where it is.
     */
    private Location leashTarget(Mascot m) {
        if (m.leashProxy == null) return null;
        if (!m.leashProxy.isValid() || !m.leashProxy.isLeashed()) {
            dropLeash(m);
            return null;
        }
        Entity holder = m.leashProxy.getLeashHolder();
        if (holder instanceof Player hp && hp.isOnline()) return hp.getLocation();
        return m.body != null ? m.body.getLocation() : null;
    }

    /** The lead is gone (taken off, broken, chunk unloaded...): clean up and hand the lead back. */
    private void dropLeash(Mascot m) {
        LivingEntity proxy = m.leashProxy;
        m.leashProxy = null;
        if (proxy != null) {
            try {
                if (proxy.isLeashed()) proxy.setLeashHolder(null);
            } catch (Exception ignored) {
            }
            proxy.remove();
        }
        ItemStack lead = new ItemStack(Material.LEAD);
        Player holder = m.leashPlayer == null ? null : Bukkit.getPlayer(m.leashPlayer);
        if (holder != null && holder.isOnline()) giveBack(holder, lead);
        else if (m.body != null && m.body.isValid()) m.body.getWorld().dropItemNaturally(m.body.getLocation(), lead);
        m.leashPlayer = null;
    }

    /** Right-click the mascot with a lead: put it on (or take it off again). */
    private void useLead(Player p, Mascot m, ItemStack hand, boolean owner) {
        boolean leashed = m.leashProxy != null && m.leashProxy.isValid() && m.leashProxy.isLeashed();
        if (leashed) {
            boolean holder = m.leashPlayer != null && m.leashPlayer.equals(p.getUniqueId());
            if (holder || owner) {
                dropLeash(m);
                p.sendActionBar(Component.text("You took the lead off the mascot.", NamedTextColor.GRAY));
            }
            return;
        }
        if (!owner && !getConfig().getBoolean("mascot.others-can-leash", false)) return;

        Chicken proxy = m.body.getWorld().spawn(m.body.getLocation().add(0, 0.8, 0), Chicken.class, c -> {
            c.setPersistent(false);
            c.setBaby();
            c.setAI(false);
            c.setSilent(true);
            c.setInvisible(true);
            c.setInvulnerable(true);
            c.setCollidable(false);
            c.setGravity(false);
            c.addScoreboardTag(MASCOT_TAG);
        });
        if (!proxy.setLeashHolder(p)) {
            proxy.remove();
            p.sendActionBar(Component.text("The mascot can't be leashed right now.", NamedTextColor.RED));
            return;
        }
        m.leashProxy = proxy;
        m.leashPlayer = p.getUniqueId();
        takeOne(p, hand);
        p.sendActionBar(Component.text("The mascot is on a lead and follows you now.", NamedTextColor.LIGHT_PURPLE));
    }

    /** A lead that gets broken (too far away, holder gone): give it back and let the mascot go free. */
    @EventHandler
    public void onMascotUnleash(EntityUnleashEvent e) {
        Entity ent = e.getEntity();
        if (!isMascotEntity(ent)) return;
        e.setDropLeash(false); // we hand the lead back ourselves
        Mascot m = mascotOf(ent);
        if (m != null && m.leashProxy != null && m.leashProxy.getUniqueId().equals(ent.getUniqueId())) {
            Bukkit.getScheduler().runTask(this, () -> dropLeash(m));
        }
    }

    /** When the person the mascot is with sits in a boat with a free seat, the mascot climbs in too. */
    private void handleBoat(Mascot m, UUID id, Player owner) {
        Player mate = null;
        if (m.leashProxy != null && m.leashProxy.isValid() && m.leashProxy.isLeashed()
                && m.leashProxy.getLeashHolder() instanceof Player holder) {
            mate = holder;
        } else if (!stay.contains(id)) {
            mate = owner;
        }
        Entity vehicle = mate == null ? null : mate.getVehicle();
        if (vehicle instanceof Boat boat) {
            if (!boat.getPassengers().contains(m.body)) {
                int seats = boat instanceof ChestBoat ? 1 : 2;
                if (boat.getPassengers().size() < seats) boat.addPassenger(m.body);
            }
        } else if (m.body.getVehicle() instanceof Boat) {
            m.body.leaveVehicle(); // the person got out, so the mascot hops out as well
        }
    }

    // ------------------------------------------------------------ mascot interaction

    private List<Mascot> allMascots() {
        List<Mascot> all = new ArrayList<>(mascots.values());
        all.addAll(babyMascots.values());
        return all;
    }

    private Mascot mascotOf(Entity entity) {
        for (Mascot m : allMascots()) {
            if (m.body != null && m.body.getUniqueId().equals(entity.getUniqueId())) return m;
            if (m.tomb != null && m.tomb.getUniqueId().equals(entity.getUniqueId())) return m;
            if (m.leashProxy != null && m.leashProxy.getUniqueId().equals(entity.getUniqueId())) return m;
        }
        return null;
    }

    private static boolean isMascotEntity(Entity e) {
        return e.getScoreboardTags().contains(MASCOT_TAG);
    }

    private static String describeCause(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) damager = shooter;
            String type = damager.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            return "Killed by a " + type;
        }
        return switch (e.getCause()) {
            case LAVA -> "Burned in lava";
            case FIRE, FIRE_TICK, HOT_FLOOR -> "Burned to death";
            case FALL -> "Fell from a height";
            case VOID -> "Fell into the void";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> "Blown up";
            case MAGIC, POISON, WITHER -> "Killed by magic";
            case LIGHTNING -> "Struck by lightning";
            default -> "It died";
        };
    }

    /**
     * Damage never reaches the entity itself - we keep our own health for the mascot so that
     * "death" is something we control (a grave and a rebirth) instead of the entity just vanishing.
     */
    @EventHandler(ignoreCancelled = false)
    public void onMascotDamage(EntityDamageEvent e) {
        Entity ent = e.getEntity();
        if (!isMascotEntity(ent)) return;
        e.setCancelled(true);

        Mascot m = mascotOf(ent);
        if (m == null || m.isGrave) return;
        if (!getConfig().getBoolean("mascot.can-be-killed", true)) return;
        switch (e.getCause()) {
            case SUFFOCATION, DROWNING, CRAMMING, FREEZE -> {
                return;
            }
            default -> {
            }
        }
        double dmg = e.getFinalDamage();
        if (dmg <= 0) return;
        switch (e.getCause()) {
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE -> {
                long nowMs = System.currentTimeMillis();
                if (nowMs - m.lastFireHit < 500L) return; // no damage flood every tick
                m.lastFireHit = nowMs;
                dmg *= getConfig().getDouble("mascot.fire-damage-multiplier", 0.5);
            }
            default -> {
            }
        }
        if (m.baby) {
            hurtBaby(m, e.getCause() == EntityDamageEvent.DamageCause.POISON ? 0 : dmg);
            return;
        }

        Player killer = null;
        if (e instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Player pl) killer = pl;
            else if (damager instanceof Projectile pr && pr.getShooter() instanceof Player pl2) killer = pl2;
        }

        String why = describeCause(e);
        UUID id = m.owner;
        double left = hp(id) - dmg;
        if (e.getCause() == EntityDamageEvent.DamageCause.POISON) left = Math.max(left, 1.0); // poison never kills, like in vanilla
        m.lastDamage = System.currentTimeMillis();
        m.nameDirty = true;
        dataDirty = true;
        if (e instanceof EntityDamageByEntityEvent hitBy && getConfig().getBoolean("mascot.pushable", true)) {
            Location from = hitBy.getDamager().getLocation();
            double kdx = m.body.getLocation().getX() - from.getX();
            double kdz = m.body.getLocation().getZ() - from.getZ();
            double kd = Math.hypot(kdx, kdz);
            if (kd > 0.01) {
                m.kx = kdx / kd * 0.4;
                m.kz = kdz / kd * 0.4;
                if (m.vy <= 0) m.vy = 0.25; // small hop
            }
        }
        m.body.playHurtAnimation(0f);
        m.body.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                m.body.getLocation().add(0, 1.2, 0), 6, 0.3, 0.3, 0.3);
        if (left <= 0) {
            killMascot(m, killer, why);
        } else {
            hpMap.put(id, left);
        }
    }

    /** A player who killed a mascot drops their head when they die - that is how the mascot gets revived. */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (!getConfig().getBoolean("mascot.revive.killer-head-drops", true)) return;
        Player dead = e.getEntity();
        if (!killers.containsValue(dead.getUniqueId())) return;
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(dead.getPlayerProfile());
        meta.displayName(Component.text(dead.getName() + "'s Head", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        head.setItemMeta(meta);
        e.getDrops().add(head);
    }

    /**
     * Right-click a mascot:
     *  - food in hand: feed it (heals it) - anyone, unless turned off in the config
     *  - armour in hand: put it on - the owner only
     *  - sneaking with an empty hand: take one piece of armour off it - anyone, unless turned off
     *  - at a grave: the head of the player who killed it brings it back (staff can use /live mascot revive)
     */
    @EventHandler(ignoreCancelled = false)
    public void onMascotInteract(PlayerInteractEntityEvent e) {
        Entity clicked = e.getRightClicked();
        if (!isMascotEntity(clicked)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        Mascot m = mascotOf(clicked);
        if (m == null) return;
        if (m.baby) {
            babyClick(p, m);
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        boolean owner = m.owner.equals(p.getUniqueId());
        Material breedItem = Material.matchMaterial(getConfig().getString("mascot.breeding.item", "GOLDEN_APPLE"));
        if (owner && breedItem != null && hand.getType() == breedItem
                && getConfig().getBoolean("mascot.breeding.enabled", true)) {
            loveMascot(p, m, hand);
            return;
        }

        if (m.isGrave) {
            graveClick(p, m, hand);
            return;
        }

        if (hand.getType() == Material.LEAD) {
            useLead(p, m, hand, owner);
            return;
        }

        boolean canHold = getConfig().getBoolean("mascot.can-hold-item", true);
        if (hand.getType().isAir()) {
            if (!p.isSneaking() && owner && getConfig().getBoolean("mascot.click-to-toggle-stay", true)) {
                setFollow(p, stay.contains(m.owner)); // right-click with an empty hand: stay here / follow me
                return;
            }
            if (p.isSneaking()) {
                Map<EquipmentSlot, ItemStack> mine = customArmor.get(m.owner);
                boolean holding = mine != null && mine.containsKey(EquipmentSlot.HAND);
                if (holding && (owner || getConfig().getBoolean("mascot.others-can-take-item", true))) {
                    takeHeld(p, m); // the held item has its own rule, separate from the armour
                } else if (owner || getConfig().getBoolean("mascot.others-can-take-armor", false)) {
                    takeArmor(p, m);
                }
            }
            return;
        }
        if (owner && canHold && p.isSneaking()) { // sneak + right-click with an item: it holds that item
            holdItem(p, m, hand);
            return;
        }
        EquipmentSlot slot = hand.getType().getEquipmentSlot();
        if (slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
                || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET) {
            if (owner) equipArmor(p, m, slot, hand);
        } else if (hand.getType() == Material.POTION) {
            if (owner || getConfig().getBoolean("mascot.others-can-feed", true)) givePotion(p, m, hand);
        } else if (hand.getType().isEdible()) {
            if (owner || getConfig().getBoolean("mascot.others-can-feed", true)) feedMascot(p, m, hand);
        } else if (owner && canHold) {
            holdItem(p, m, hand); // any other item: the mascot holds it
        }
    }

    /** The mascot holds ONE item in its hand (a sword, a torch, a flower...). The owner gives and takes it. */
    private void holdItem(Player p, Mascot m, ItemStack hand) {
        ItemStack one = hand.clone();
        one.setAmount(1);
        Map<EquipmentSlot, ItemStack> mine =
                customArmor.computeIfAbsent(m.owner, k -> new EnumMap<>(EquipmentSlot.class));
        ItemStack old = mine.put(EquipmentSlot.HAND, one);
        takeOne(p, hand);
        if (old != null) giveBack(p, old); // swapping: the old item comes back to the owner
        EntityEquipment eq = m.body.getEquipment();
        if (eq != null) eq.setItemInMainHand(one.clone());
        saveData();
        p.getWorld().playSound(m.body.getLocation(), Sound.ENTITY_ITEM_PICKUP, sfx("interact", 1f), 1f);
        p.sendActionBar(Component.text("Your mascot is holding it now. Sneak + right-click with an empty hand to take it back.",
                NamedTextColor.LIGHT_PURPLE));
    }

    private void takeHeld(Player p, Mascot m) {
        Map<EquipmentSlot, ItemStack> mine = customArmor.get(m.owner);
        ItemStack taken = mine == null ? null : mine.remove(EquipmentSlot.HAND);
        if (mine != null && mine.isEmpty()) customArmor.remove(m.owner);
        if (taken == null) return;
        giveBack(p, taken);
        EntityEquipment eq = m.body.getEquipment();
        if (eq != null) eq.setItemInMainHand(new ItemStack(Material.AIR));
        saveData();
        p.getWorld().playSound(m.body.getLocation(), Sound.ENTITY_ITEM_PICKUP, sfx("interact", 1f), 0.8f);
        p.sendActionBar(Component.text("You took the item back.", NamedTextColor.GRAY));
    }

    /** A mascot holding a Totem of Undying pops it to save its streamer from a deadly hit. */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
    public void onOwnerFatalDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!getConfig().getBoolean("mascot.totem-save", true)) return;
        if (p.getHealth() - e.getFinalDamage() > 0) return;
        Mascot m = mascots.get(p.getUniqueId());
        if (m == null || m.isGrave || m.body == null || !m.body.isValid()) return;
        Map<EquipmentSlot, ItemStack> mine = customArmor.get(p.getUniqueId());
        ItemStack held = mine == null ? null : mine.get(EquipmentSlot.HAND);
        if (held == null || held.getType() != Material.TOTEM_OF_UNDYING) return;
        // the player's own totem works first, like normal
        if (p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return;
        if (m.body.getWorld() != p.getWorld()
                || m.body.getLocation().distance(p.getLocation()) > getConfig().getDouble("mascot.totem-range", 40.0)) return;

        e.setCancelled(true);
        mine.remove(EquipmentSlot.HAND);
        if (mine.isEmpty()) customArmor.remove(p.getUniqueId());
        EntityEquipment eq = m.body.getEquipment();
        if (eq != null) eq.setItemInMainHand(new ItemStack(Material.AIR));
        p.setHealth(1.0);
        p.clearActivePotionEffects();
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
        Location at = p.getLocation().add(0, 1, 0);
        at.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at, 60, 0.4, 0.7, 0.4, 0.4);
        Location at2 = m.body.getLocation().add(0, 1, 0);
        at2.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at2, 30, 0.3, 0.5, 0.3, 0.3);
        at.getWorld().playSound(at, Sound.ITEM_TOTEM_USE, SoundCategory.PLAYERS, sfx("totem", 1f), 1f);
        p.sendActionBar(Component.text("Your mascot popped its totem and saved you!", NamedTextColor.GOLD));
        saveData();
    }

    /** Healing from potions (instant health) goes into the mascot's own health, not the hidden real one. */
    @EventHandler
    public void onMascotRegain(EntityRegainHealthEvent e) {
        if (!isMascotEntity(e.getEntity())) return;
        e.setCancelled(true);
        Mascot m = mascotOf(e.getEntity());
        if (m == null || m.isGrave) return;
        healMascot(m, e.getAmount());
    }

    private void healMascot(Mascot m, double amount) {
        UUID id = m.owner;
        double max = maxHp();
        double cur = hp(id);
        if (cur >= max || amount <= 0) return;
        hpMap.put(id, Math.min(max, cur + amount));
        m.nameDirty = true;
        dataDirty = true;
    }

    /** Right-click with a drinkable potion: the mascot gets its effects (fire resistance, regeneration...). */
    private void givePotion(Player p, Mascot m, ItemStack hand) {
        if (!(hand.getItemMeta() instanceof PotionMeta pm)) return;
        List<PotionEffect> effects = new ArrayList<>(pm.getCustomEffects());
        PotionType base = pm.getBasePotionType();
        if (base != null) effects.addAll(base.getPotionEffects());
        if (effects.isEmpty()) return;
        for (PotionEffect ef : effects) {
            PotionEffectType t = ef.getType();
            if (t.equals(PotionEffectType.INSTANT_HEALTH)) {
                healMascot(m, 4.0 * (1 << Math.min(ef.getAmplifier(), 6)));
            } else if (t.equals(PotionEffectType.INSTANT_DAMAGE)) {
                m.body.damage(6.0 * (1 << Math.min(ef.getAmplifier(), 6)));
            } else {
                m.body.addPotionEffect(ef);
            }
        }
        takeOne(p, hand);
        giveBack(p, new ItemStack(Material.GLASS_BOTTLE));
        p.getWorld().playSound(m.body.getLocation(), Sound.ENTITY_GENERIC_DRINK, sfx("interact", 1f), 1f);
        p.sendActionBar(Component.text("The mascot drank the potion.", NamedTextColor.LIGHT_PURPLE));
    }

    private void takeOne(Player p, ItemStack hand) {
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ItemStack rest = hand.clone();
        rest.setAmount(hand.getAmount() - 1);
        p.getInventory().setItemInMainHand(rest.getAmount() <= 0 ? new ItemStack(Material.AIR) : rest);
    }

    private void giveBack(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItem(p.getLocation(), rest);
        }
    }

    private void equipArmor(Player p, Mascot m, EquipmentSlot slot, ItemStack hand) {
        ItemStack piece = hand.clone();
        piece.setAmount(1);
        Map<EquipmentSlot, ItemStack> mine =
                customArmor.computeIfAbsent(m.owner, k -> new EnumMap<>(EquipmentSlot.class));
        ItemStack old = mine.put(slot, piece);

        takeOne(p, hand);
        if (old != null) giveBack(p, old);

        EntityEquipment eq = m.body.getEquipment();
        if (eq != null) eq.setItem(slot, piece.clone());
        saveData();
        p.getWorld().playSound(m.body.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, sfx("interact", 1f), 1f);
        p.sendActionBar(Component.text("Your mascot is wearing it now.", NamedTextColor.LIGHT_PURPLE));
    }

    /** Takes one piece of the armour somebody put on the mascot (helmet first). */
    private void takeArmor(Player p, Mascot m) {
        Map<EquipmentSlot, ItemStack> mine = customArmor.get(m.owner);
        ItemStack taken = null;
        if (mine != null) {
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                taken = mine.remove(slot);
                if (taken != null) break;
            }
            if (mine.isEmpty()) customArmor.remove(m.owner);
        }
        if (taken == null) {
            p.sendActionBar(Component.text("Nothing to take - it only has its default outfit.",
                    NamedTextColor.GRAY));
            return;
        }
        giveBack(p, taken);
        dressMascot(m.body, m.rgb, m.owner, m.leather);
        saveData();
        p.getWorld().playSound(m.body.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, sfx("interact", 1f), 0.7f);
        p.sendActionBar(Component.text("You took a piece of armour off the mascot.", NamedTextColor.GRAY));
    }

    private void feedMascot(Player p, Mascot m, ItemStack hand) {
        Material food = hand.getType();
        takeOne(p, hand);
        int count = fed.merge(m.owner, 1, Integer::sum);

        double heal = Math.max(0.0, getConfig().getDouble("mascot.feed-heal", 4.0));
        hpMap.put(m.owner, Math.min(maxHp(), hp(m.owner) + heal));
        m.nameDirty = true;

        Location mouth = m.body.getLocation().add(0, 1.9, 0);
        m.body.getWorld().spawnParticle(Particle.HEART, mouth, 4, 0.3, 0.2, 0.3);
        m.body.getWorld().playSound(m.body.getLocation(), Sound.ENTITY_GENERIC_EAT, sfx("interact", 1f), 1f);
        m.body.swingMainHand();
        saveData();
        p.sendActionBar(Component.text("The mascot enjoyed the ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.translatable(food.translationKey()))
                .append(Component.text("! (fed " + count + (count == 1 ? " time)" : " times)"))));
    }

    // ------------------------------------------------------------ death and rebirth

    /** The mascot dies: lightning, armour drops, and a grave is left behind. */
    private void killMascot(Mascot m, Player killer, String cause) {
        UUID id = m.owner;
        if (m.body == null) return;
        Location at = m.body.getLocation();
        World w = at.getWorld();
        if (w == null) return;

        deadSince.put(id, System.currentTimeMillis());
        graves.put(id, Home.of(at));
        if (killer != null) {
            killers.put(id, killer.getUniqueId());
            killerNames.put(id, killer.getName());
            deathCause.remove(id);
        } else {
            killers.remove(id);
            killerNames.remove(id);
            deathCause.put(id, cause != null ? cause : "It died");
        }
        hpMap.remove(id);

        if (getConfig().getBoolean("mascot.drop-armor-on-death", true)) {
            Map<EquipmentSlot, ItemStack> mine = customArmor.remove(id);
            if (mine != null) {
                for (ItemStack it : mine.values()) w.dropItemNaturally(at, it);
            }
        }

        w.strikeLightningEffect(at);
        w.spawnParticle(Particle.SOUL, at.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
        w.playSound(at, Sound.ENTITY_ALLAY_DEATH, sfx("death", 1f), 0.6f);

        if (getConfig().getBoolean("mascot.announce-death", true)) {
            String name = Bukkit.getOfflinePlayer(id).getName();
            if (name == null) name = "A streamer";
            String text = killer != null
                    ? killer.getName() + " killed " + name + "'s mascot!"
                    : name + "'s mascot died. (" + (cause != null ? cause : "It died") + ")";
            Bukkit.broadcast(Component.text(text, NamedTextColor.RED));
        }

        discard(m);
        mascots.remove(id);
        saveData();
    }

    /** Brings a dead mascot back. {@code reason} finishes the sentence "...has been reborn <reason>". */
    private void reviveMascot(UUID id, String reason) {
        if (!deadSince.containsKey(id)) return;
        Home g = graves.get(id);
        Location at = g == null ? null : g.toLocation();

        deadSince.remove(id);
        graves.remove(id);
        killers.remove(id);
        killerNames.remove(id);
        deathCause.remove(id);
        hpMap.remove(id);
        lastLive.put(id, System.currentTimeMillis()); // fresh timer after rebirth
        idleDead.remove(id);

        Mascot old = mascots.remove(id);
        if (old != null) {
            if (at == null && old.tomb != null) at = old.tomb.getLocation();
            old.remove();
        }
        if (at != null && at.getWorld() != null) {
            World w = at.getWorld();
            w.strikeLightningEffect(at);
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, at.clone().add(0, 1, 0), 80, 0.5, 0.8, 0.5, 0.3);
            w.playSound(at, Sound.ITEM_TOTEM_USE, sfx("revive", 1f), 1f);
        }
        if (getConfig().getBoolean("mascot.revive.announce", true)) {
            String name = Bukkit.getOfflinePlayer(id).getName();
            if (name == null) name = "A streamer";
            Bukkit.broadcast(Component.text(name + "'s mascot has been reborn " + reason,
                    NamedTextColor.LIGHT_PURPLE));
        }
        saveData();
    }

    /** True if the item is the player head of the given player. */
    private boolean isHeadOf(ItemStack item, UUID id, String name) {
        if (item.getType() != Material.PLAYER_HEAD) return false;
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return false;
        PlayerProfile profile = meta.getPlayerProfile();
        if (profile == null) return false;
        if (id.equals(profile.getId())) return true;
        return name != null && profile.getName() != null && profile.getName().equalsIgnoreCase(name);
    }

    private void graveClick(Player p, Mascot m, ItemStack hand) {
        UUID owner = m.owner;
        UUID killerId = killers.get(owner);

        if (idleDead.contains(owner)) {
            p.sendActionBar(Component.text("It faded away from a lack of streams. It returns when its streamer goes live, or staff can revive it.",
                    NamedTextColor.GRAY));
            return;
        }

        if (killerId != null) {
            // killed by a player: only that player's head brings it back
            String killerName = killerNames.getOrDefault(owner, "its killer");
            if (isHeadOf(hand, killerId, killerName)) {
                takeOne(p, hand);
                reviveMascot(owner, "with the head of " + killerName + ", thanks to " + p.getName() + "!");
                return;
            }
            p.sendActionBar(Component.text("It needs the head of " + killerName + ", who killed it.",
                    NamedTextColor.GRAY));
            return;
        }

        // killed by a mob, lava, a fall...: a Totem of Undying brings it back
        boolean totem = getConfig().getBoolean("mascot.revive.totem", true);
        if (totem && hand.getType() == Material.TOTEM_OF_UNDYING) {
            takeOne(p, hand);
            reviveMascot(owner, "with a Totem of Undying, thanks to " + p.getName() + "!");
            return;
        }
        p.sendActionBar(Component.text(totem ? "It needs a Totem of Undying." : "Only staff can bring it back.",
                NamedTextColor.GRAY));
    }

    private Mascot createGrave(UUID id, Location at) {
        Mascot m = new Mascot(id, links.get(id), colorFor(id));
        m.isGrave = true;
        World w = at.getWorld();
        if (w == null) return null;
        try {
            m.tomb = w.spawn(at, Interaction.class, i -> {
                i.setPersistent(false);
                i.addScoreboardTag(MASCOT_TAG);
                i.setInteractionWidth(1.0f);
                i.setInteractionHeight(1.6f);
                i.setResponsive(true);
            });
            m.skull = w.spawn(at.clone().add(0, 0.9, 0), ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(MASCOT_TAG);
                d.setItemStack(new ItemStack(Material.WITHER_SKELETON_SKULL));
                d.setBillboard(Display.Billboard.VERTICAL);
            });
            m.board = w.spawn(at.clone().add(0, 1.9, 0), TextDisplay.class, t -> {
                t.setPersistent(false);
                t.addScoreboardTag(MASCOT_TAG);
                t.setBillboard(Display.Billboard.CENTER);
                t.setAlignment(TextDisplay.TextAlignment.CENTER);
                t.setLineWidth(220);
                t.setBackgroundColor(Color.fromARGB(170, 20, 20, 20));
                t.text(Component.empty());
            });
        } catch (Exception ex) {
            if (!warnedSpawn) {
                warnedSpawn = true;
                getLogger().warning("Could not spawn a mascot grave: " + ex);
            }
            m.remove();
            return null;
        }
        return m;
    }

    private void renderGrave(Mascot m, long now) {
        UUID id = m.owner;
        String name = Bukkit.getOfflinePlayer(id).getName();
        if (name == null) name = "Streamer";
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("☠ " + name + "'s mascot ☠", NamedTextColor.RED, TextDecoration.BOLD));

        String killerName = killerNames.get(id);
        if (killers.containsKey(id) && killerName != null) {
            lines.add(Component.text("Killed by " + killerName, NamedTextColor.GRAY));
            lines.add(Component.text("Right-click with " + killerName + "'s head", NamedTextColor.YELLOW));
        } else {
            lines.add(Component.text(deathCause.getOrDefault(id, "It died"), NamedTextColor.GRAY));
            if (getConfig().getBoolean("mascot.revive.totem", true)) {
                lines.add(Component.text("Right-click with a Totem of Undying", NamedTextColor.YELLOW));
            } else {
                lines.add(Component.text("Only staff can bring it back", NamedTextColor.GRAY));
            }
        }

        Component text = Component.join(JoinConfiguration.newlines(), lines);
        if (!text.equals(m.lastGrave)) {
            m.lastGrave = text;
            m.board.text(text);
        }
        if (m.ticks % 4 == 0 && m.tomb != null) {
            Location l = m.tomb.getLocation().add(0, 1.0, 0);
            l.getWorld().spawnParticle(Particle.SOUL, l, 2, 0.3, 0.5, 0.3, 0.01);
        }
    }

    /** A saved spot for a mascot that is set to stay. */
    private record Home(String world, double x, double y, double z, float yaw) {
        static Home of(Location l) {
            return new Home(l.getWorld() == null ? "" : l.getWorld().getName(),
                    l.getX(), l.getY(), l.getZ(), l.getYaw());
        }

        Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, 0f);
        }
    }

    private List<String> colorNames() {
        List<String> out = new ArrayList<>();
        for (Map<?, ?> e : getConfig().getMapList("mascot.skins")) {
            out.add(text(e.get("color")).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Accepts a number (1..N) or a colour name from mascot.skins. Returns -1 if unknown. */
    private int parseColor(String arg) {
        String a = arg.toLowerCase(Locale.ROOT).replace('-', '_').trim();
        int count = palette().length;
        try {
            int n = Integer.parseInt(a);
            if (n >= 1 && n <= count) return n - 1;
        } catch (NumberFormatException ignored) {
        }
        List<String> names = colorNames();
        for (int i = 0; i < names.size() && i < count; i++) {
            if (names.get(i).replace('-', '_').equals(a)) return i;
        }
        return -1;
    }

    /** Clickable buttons that fake a Twitch event on your own mascot. */
    private void sendTestButtons(Player p) {
        String[][] items = {
                {"Sub", "sub"}, {"Resub", "resub"}, {"Gift", "gift"},
                {"Community", "community"}, {"Raid", "raid"}, {"Cheer", "cheer"}
        };
        Component row = Component.text("Test an event: ", NamedTextColor.GRAY);
        for (String[] it : items) {
            row = row.append(Component.text("[" + it[0] + "]", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/live mascot test " + it[1]))
                    .hoverEvent(HoverEvent.showText(Component.text("Fake a " + it[0].toLowerCase(Locale.ROOT)
                            + " event on your mascot"))))
                    .append(Component.text(" "));
        }
        p.sendMessage(row);
    }

    private boolean canUseMascot(Player p) {
        UUID id = p.getUniqueId();
        if (p.hasPermission("livetag.admin")) return true;
        if (!links.containsKey(id)) {
            p.sendMessage(Component.text("Your Twitch account isn't linked yet - ask a staff member.",
                    NamedTextColor.RED));
            return false;
        }
        if (getConfig().getBoolean("mascot.require-first-stream", true) && !streamed.contains(id)) {
            p.sendMessage(Component.text("Your mascot arrives the first time you go live on Twitch.",
                    NamedTextColor.RED));
            return false;
        }
        return true;
    }

    /** follow = true: mascot walks with the streamer. follow = false: it stays where it stands now. */
    private void setFollow(Player p, boolean follow) {
        UUID id = p.getUniqueId();
        if (follow) {
            stay.remove(id);
            homes.remove(id);
        } else {
            Mascot m = mascots.get(id);
            Location at = (m != null && m.valid()) ? m.body.getLocation() : mascotLocation(p);
            stay.add(id);
            homes.put(id, Home.of(at));
        }
        saveData();
        p.sendMessage(Component.text(follow
                        ? "Your mascot is now following you."
                        : "Your mascot will stay where it is. Use /live mascot here to move it.",
                follow ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GRAY));
    }

    /** /live mascot [on|off|follow|here|preview] */
    private void handleMascot(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        UUID id = p.getUniqueId();
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "toggle";

        if (action.equals("intro")) { // staff: replay the entrance on your own mascot
            if (!p.hasPermission("livetag.admin")) {
                p.sendMessage(Component.text(msg("no-permission"), NamedTextColor.RED));
                return;
            }
            Mascot im = mascots.get(id);
            if (im == null || im.isGrave || !im.valid()) {
                p.sendMessage(Component.text("You have no mascot out right now (try /live mascot preview).", NamedTextColor.RED));
                return;
            }
            playIntro(im);
            return;
        }

        if (action.equals("baby")) { // staff test: a baby of you and yourself
            if (!p.hasPermission("livetag.admin")) {
                p.sendMessage(Component.text(msg("no-permission"), NamedTextColor.RED));
                return;
            }
            if (!links.containsKey(id)) {
                p.sendMessage(Component.text("Link your Twitch first (the baby needs a channel).", NamedTextColor.RED));
                return;
            }
            if (hasBaby(id)) {
                for (Baby bb2 : new ArrayList<>(babies.values())) {
                    if (bb2.a().equals(id) || bb2.b().equals(id)) {
                        Mascot old = babyMascots.remove(bb2.id());
                        if (old != null) discard(old);
                        babies.remove(bb2.id());
                    }
                }
                dataDirty = true;
                p.sendMessage(Component.text("Test baby removed.", NamedTextColor.GRAY));
                return;
            }
            UUID bid2 = UUID.randomUUID();
            babies.put(bid2, new Baby(bid2, id, id, System.currentTimeMillis()));
            dataDirty = true;
            tickMascots();
            p.sendMessage(Component.text("Test baby spawned. Run /live mascot baby again to remove it.", NamedTextColor.LIGHT_PURPLE));
            return;
        }

        if (action.equals("preview")) {
            if (!p.hasPermission("livetag.admin")) {
                p.sendMessage(Component.text(msg("no-permission"), NamedTextColor.RED));
                return;
            }
            if (preview.remove(id)) {
                p.sendMessage(Component.text("Mascot preview off.", NamedTextColor.GRAY));
                return;
            }
            preview.add(id);
            Mascot m = mascots.get(id);
            if (m == null) {
                m = createMascot(id, mascotLocation(p));
                if (m != null) mascots.put(id, m);
            }
            if (m != null) {
                long exp = System.currentTimeMillis() + 60_000L;
                String[][] sample = {
                        {"Viewer1", "hello chat!"},
                        {"Viewer2", "nice stream"},
                        {"Viewer3", "the mascot looks great"}
                };
                for (String[] s : sample) {
                    addLine(m, bubbleLine(s[0], s[1], m.rgb), exp, 6);
                }
            }
            p.sendMessage(Component.text("Mascot preview on for 2 minutes.", NamedTextColor.LIGHT_PURPLE));
            sendTestButtons(p);
            Bukkit.getScheduler().runTaskLater(this, () -> preview.remove(id), 20L * 120L);
            return;
        }

        if (action.equals("color")) {
            if (!p.hasPermission("livetag.admin")) {
                p.sendMessage(Component.text(msg("no-permission"), NamedTextColor.RED));
                return;
            }
            if (args.length < 4) {
                p.sendMessage(Component.text("Usage: /live mascot color <player> <color or number 1-"
                        + palette().length + ">", NamedTextColor.YELLOW));
                return;
            }
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[2]);
            if (op == null || !links.containsKey(op.getUniqueId())) {
                p.sendMessage(Component.text("'" + args[2] + "' is not a linked streamer.", NamedTextColor.RED));
                return;
            }
            int idx = parseColor(args[3]);
            if (idx < 0) {
                p.sendMessage(Component.text("Unknown colour '" + args[3] + "'. Press Tab to see them.",
                        NamedTextColor.RED));
                return;
            }
            UUID target = op.getUniqueId();
            for (Map.Entry<UUID, Integer> en : colorIndex.entrySet()) {
                if (en.getValue() == idx && !en.getKey().equals(target)) {
                    String other = Bukkit.getOfflinePlayer(en.getKey()).getName();
                    p.sendMessage(Component.text("That colour already belongs to "
                            + (other != null ? other : "another streamer") + ".", NamedTextColor.RED));
                    return;
                }
            }
            colorIndex.put(target, idx);
            saveData();
            Mascot old = mascots.remove(target);
            if (old != null) discard(old); // it comes back in the new colour a moment later
            p.sendMessage(Component.text(op.getName() + " now has colour #" + (idx + 1) + ".",
                    NamedTextColor.LIGHT_PURPLE));
            return;
        }

        if (action.equals("debug")) {
            if (!p.hasPermission("livetag.admin")) {
                p.sendMessage(Component.text(msg("no-permission"), NamedTextColor.RED));
                return;
            }
            Mascot dm = mascots.get(id);
            String[] lines = {
                    "mascot.enabled: " + getConfig().getBoolean("mascot.enabled", true),
                    "linked to Twitch: " + links.containsKey(id),
                    "has streamed (first stream): " + streamed.contains(id) + "  (require-first-stream: "
                            + getConfig().getBoolean("mascot.require-first-stream", true) + ")",
                    "mascot switched OFF by you: " + mascotOff.contains(id),
                    "always-visible: " + getConfig().getBoolean("mascot.always-visible", true),
                    "twitch live now: " + twitchLive.contains(id),
                    "dead / grave: " + deadSince.containsKey(id),
                    "mascot entity exists: " + (dm != null && dm.valid()),
                    "follow mode: " + (stay.contains(id) ? "STAY" : "follow")
            };
            for (String l : lines) p.sendMessage(Component.text(l, NamedTextColor.GRAY));
            return;
        }

        if (action.equals("test")) {
            if (!p.hasPermission("livetag.admin")) {
                p.sendMessage(Component.text(msg("no-permission"), NamedTextColor.RED));
                return;
            }
            if (args.length < 3) {
                sendTestButtons(p);
                return;
            }
            Mascot tm = mascots.get(id);
            if (tm == null || tm.isGrave || !tm.valid()) {
                p.sendMessage(Component.text("You need a mascot first: run /live mascot preview.", NamedTextColor.RED));
                return;
            }
            String fake = "preview:" + id;
            TwitchChat.Message ev = switch (args[2].toLowerCase(Locale.ROOT)) {
                case "sub" -> new TwitchChat.Message(fake, "Viewer1", "Viewer1 subscribed", "sub", 0);
                case "resub" -> new TwitchChat.Message(fake, "Viewer2", "Viewer2 resubscribed for 12 months", "resub", 0);
                case "gift" -> new TwitchChat.Message(fake, "Viewer3", "Viewer3 gifted a sub to Viewer4", "subgift", 0);
                case "community" -> new TwitchChat.Message(fake, "Viewer5", "Viewer5 gifted 10 subs to the community",
                        "submysterygift", 10);
                case "raid" -> new TwitchChat.Message(fake, "Raider", "Raider raided with 120 viewers", "raid", 120);
                case "cheer" -> new TwitchChat.Message(fake, "Viewer6", "Viewer6 cheered 500 bits", "cheer", 500);
                default -> null;
            };
            if (ev == null) {
                sendTestButtons(p);
                return;
            }
            handleTwitchEvent(ev, System.currentTimeMillis());
            return;
        }

        if (action.equals("give")) {
            if (!p.hasPermission("livetag.admin")) {
                p.sendMessage(Component.text(msg("no-permission"), NamedTextColor.RED));
                return;
            }
            if (args.length < 3) {
                p.sendMessage(Component.text("Usage: /live mascot give <player>", NamedTextColor.YELLOW));
                return;
            }
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[2]);
            if (op == null || !links.containsKey(op.getUniqueId())) {
                p.sendMessage(Component.text("'" + args[2] + "' is not a linked streamer.", NamedTextColor.RED));
                return;
            }
            UUID target = op.getUniqueId();
            streamed.add(target); // counts as having streamed, so the mascot may exist
            lastLive.put(target, System.currentTimeMillis()); // fresh 48h timer
            mascotOff.remove(target);
            if (deadSince.containsKey(target)) reviveMascot(target, "by staff.");
            saveData();
            p.sendMessage(Component.text(op.getName() + "'s mascot is now in the world (it spawns next to "
                    + "them, so they must be online).", NamedTextColor.LIGHT_PURPLE));
            return;
        }

        if (action.equals("revive")) {
            if (!p.hasPermission("livetag.admin")) {
                p.sendMessage(Component.text(msg("no-permission"), NamedTextColor.RED));
                return;
            }
            UUID target = id;
            if (args.length > 2) {
                OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[2]);
                if (op == null) {
                    p.sendMessage(Component.text("Unknown player '" + args[2] + "'.", NamedTextColor.RED));
                    return;
                }
                target = op.getUniqueId();
            }
            if (!deadSince.containsKey(target)) {
                p.sendMessage(Component.text("That mascot isn't dead.", NamedTextColor.GRAY));
                return;
            }
            reviveMascot(target, "by staff.");
            return;
        }

        if (!canUseMascot(p)) return;

        switch (action) {
            case "follow" -> {
                boolean follow;
                if (args.length > 2) follow = !args[2].equalsIgnoreCase("off");
                else follow = stay.contains(id); // toggle
                setFollow(p, follow);
            }
            case "here" -> {
                if (!stay.contains(id)) {
                    p.sendMessage(Component.text("Your mascot is following you. Turn that off first: /live mascot follow off",
                            NamedTextColor.YELLOW));
                    return;
                }
                homes.put(id, Home.of(mascotLocation(p)));
                saveData();
                p.sendMessage(Component.text("Mascot moved next to you.", NamedTextColor.LIGHT_PURPLE));
            }
            default -> {
                boolean off;
                switch (action) {
                    case "on" -> off = false;
                    case "off" -> off = true;
                    default -> off = !mascotOff.contains(id);
                }
                if (off) mascotOff.add(id);
                else mascotOff.remove(id);
                saveData();
                p.sendMessage(Component.text("Chat mascot " + (off ? "OFF" : "ON") + ".",
                        off ? NamedTextColor.GRAY : NamedTextColor.LIGHT_PURPLE));
            }
        }
    }

    // ------------------------------------------------------------ Twitch chat (anonymous IRC, read-only)

    /** Reads Twitch chat over IRC without logging in. */
    static final class TwitchChat {
        /** event == null: a normal chat message. Otherwise a Twitch event (sub, resub, subgift, submysterygift, raid, cheer). */
        record Message(String channel, String name, String text, String event, int amount) {
        }

        private final java.util.Queue<Message> inbox = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final Set<String> wanted = ConcurrentHashMap.newKeySet();
        private final Set<String> joined = new HashSet<>();
        private final java.util.logging.Logger log;
        private volatile boolean running;
        private Thread thread;
        private java.net.Socket socket;
        private BufferedWriter out;
        private volatile long backoffMs = 2000L;

        TwitchChat(java.util.logging.Logger log) {
            this.log = log;
        }

        void start() {
            running = true;
            thread = new Thread(this::run, "ChatMate-TwitchChat");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            closeSocket();
            if (thread != null) thread.interrupt();
        }

        Message poll() {
            return inbox.poll();
        }

        void setChannels(Set<String> channels) {
            wanted.retainAll(channels);
            wanted.addAll(channels);
            syncJoins();
        }

        private synchronized void syncJoins() {
            if (out == null) return;
            try {
                for (String c : wanted) {
                    if (joined.add(c)) send("JOIN #" + c);
                }
                Iterator<String> it = joined.iterator();
                while (it.hasNext()) {
                    String c = it.next();
                    if (!wanted.contains(c)) {
                        it.remove();
                        send("PART #" + c);
                    }
                }
            } catch (IOException e) {
                closeSocket();
            }
        }

        private synchronized void send(String line) throws IOException {
            if (out == null) throw new IOException("not connected");
            out.write(line);
            out.write("\r\n");
            out.flush();
        }

        private synchronized void closeSocket() {
            out = null;
            joined.clear();
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }

        private void run() {
            while (running) {
                try {
                    javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket)
                            javax.net.ssl.SSLSocketFactory.getDefault().createSocket("irc.chat.twitch.tv", 6697);
                    s.setSoTimeout(360_000);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    synchronized (this) {
                        socket = s;
                        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                        joined.clear();
                        send("CAP REQ :twitch.tv/tags twitch.tv/commands");
                        send("PASS SCHMOOPIIE");
                        send("NICK justinfan" + (10000 + new Random().nextInt(80000)));
                    }
                    String line;
                    while (running && (line = in.readLine()) != null) {
                        handle(line);
                    }
                } catch (Exception e) {
                    if (running) log.warning("Twitch chat connection lost: " + e.getMessage());
                }
                closeSocket();
                if (!running) break;
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    break;
                }
                backoffMs = Math.min(60_000L, backoffMs * 2);
            }
        }

        private void handle(String line) {
            if (line.startsWith("PING")) {
                try {
                    send("PONG" + line.substring(4));
                } catch (IOException ignored) {
                }
                return;
            }
            if (line.contains(" 001 ")) {
                backoffMs = 2000L;
                syncJoins();
                return;
            }

            String rest = line;
            String tags = "";
            if (rest.startsWith("@")) {
                int sp = rest.indexOf(' ');
                if (sp < 0) return;
                tags = rest.substring(1, sp);
                rest = rest.substring(sp + 1);
            }
            int un = rest.indexOf(" USERNOTICE #");
            if (un >= 0) {
                handleNotice(tags, rest, un);
                return;
            }
            int pm = rest.indexOf(" PRIVMSG #");
            if (pm < 0) return;

            String nick = rest.substring(0, pm);
            if (nick.startsWith(":")) nick = nick.substring(1);
            int bang = nick.indexOf('!');
            if (bang >= 0) nick = nick.substring(0, bang);

            int chanStart = pm + " PRIVMSG #".length();
            int chanEnd = rest.indexOf(' ', chanStart);
            if (chanEnd < 0) return;
            String channel = rest.substring(chanStart, chanEnd).toLowerCase(Locale.ROOT);

            int textStart = rest.indexOf(" :", chanEnd);
            if (textStart < 0) return;
            String text = rest.substring(textStart + 2);

            String display = nick;
            int bits = 0;
            for (String tag : tags.split(";")) {
                if (tag.startsWith("display-name=") && tag.length() > 13) {
                    display = unescape(tag.substring(13));
                } else if (tag.startsWith("bits=")) {
                    try {
                        bits = Integer.parseInt(tag.substring(5));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (inbox.size() > 500) inbox.poll();
            inbox.add(new Message(channel, display, text, null, 0));
            if (bits > 0) {
                inbox.add(new Message(channel, display, display + " cheered " + bits + " bits", "cheer", bits));
            }
        }

        private static String unescape(String v) {
            return v.replace("\\s", " ").replace("\\:", ";").replace("\\r", "").replace("\\n", " ").replace("\\\\", "\\");
        }

        private static String clean(String v) {
            String t = v.replaceAll("\\p{Cntrl}", " ").trim();
            return t.length() > 40 ? t.substring(0, 40) : t;
        }

        /** Subs, resubs, gifts and raids arrive as USERNOTICE lines. */
        private void handleNotice(String tags, String rest, int un) {
            int chanStart = un + " USERNOTICE #".length();
            int chanEnd = rest.indexOf(' ', chanStart);
            String channel = (chanEnd < 0 ? rest.substring(chanStart) : rest.substring(chanStart, chanEnd))
                    .toLowerCase(Locale.ROOT);
            java.util.Map<String, String> t = new java.util.HashMap<>();
            for (String tag : tags.split(";")) {
                int eq = tag.indexOf('=');
                if (eq > 0) t.put(tag.substring(0, eq), unescape(tag.substring(eq + 1)));
            }
            String id = t.getOrDefault("msg-id", "");
            String name = clean(t.getOrDefault("display-name", t.getOrDefault("login", "Someone")));
            if (name.isEmpty()) name = "Someone";
            String text;
            int amount = 0;
            switch (id) {
                case "sub" -> {
                    String plan = t.getOrDefault("msg-param-sub-plan", "");
                    text = name + " subscribed" + (plan.equals("Prime") ? " with Prime" : "");
                }
                case "resub" -> {
                    String months = t.getOrDefault("msg-param-cumulative-months", "");
                    text = name + " resubscribed" + (months.isEmpty() ? "" : " for " + clean(months) + " months");
                }
                case "subgift" -> {
                    if (t.containsKey("msg-param-community-gift-id")) return; // part of a community gift, counted once
                    String to = clean(t.getOrDefault("msg-param-recipient-display-name", "someone"));
                    text = name + " gifted a sub to " + to;
                }
                case "submysterygift" -> {
                    try {
                        amount = Integer.parseInt(t.getOrDefault("msg-param-mass-gift-count", "1"));
                    } catch (NumberFormatException e) {
                        amount = 1;
                    }
                    text = name + " gifted " + amount + " subs to the community";
                }
                case "raid" -> {
                    try {
                        amount = Integer.parseInt(t.getOrDefault("msg-param-viewerCount", "0"));
                    } catch (NumberFormatException e) {
                        amount = 0;
                    }
                    text = clean(t.getOrDefault("msg-param-displayName", name)) + " raided with " + amount + " viewers";
                }
                default -> {
                    return;
                }
            }
            if (inbox.size() > 500) inbox.poll();
            inbox.add(new Message(channel, name, text, id, amount));
        }
    }
}
