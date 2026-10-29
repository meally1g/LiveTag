package dev.livetag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
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
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class LiveTag extends JavaPlugin implements Listener, TabExecutor {

    private static final String TEAM_NAME = "lt_live";
    private static final int BUTTON_SLOT = 13;

    private final Set<UUID> live = new HashSet<>();
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
            if (live.contains(p.getUniqueId())) apply(p, true);
        }
    }

    @Override
    public void onDisable() {
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
    }

    private void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("live", live.stream().map(UUID::toString).toList());
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

    private Component prefix() {
        return MiniMessage.miniMessage().deserialize(
                getConfig().getString("prefix", "<dark_purple><bold>[LIVE]</bold></dark_purple> "));
    }

    /**
     * A scoreboard team prefix shows the tag above the player's head.
     * The display name covers chat, and the player list name covers the tab list.
     */
    private Team team() {
        Scoreboard sb =
