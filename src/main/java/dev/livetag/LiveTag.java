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
     * A scoreboard team prefix shows the tag in the tab list AND above the
     * player's head. The display name covers chat.
     */
    private Team team() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam(TEAM_NAME);
        if (t == null) t = sb.registerNewTeam(TEAM_NAME);
        t.prefix(prefix());
        return t;
    }

    private void apply(Player p, boolean on) {
        Team t = team();
        if (on) {
            t.addEntry(p.getName());
            p.displayName(prefix().append(Component.text(p.getName())));
        } else {
            t.removeEntry(p.getName());
            p.displayName(null);
        }
    }

    private void setLive(Player p, boolean on) {
        if (on) live.add(p.getUniqueId());
        else live.remove(p.getUniqueId());
        apply(p, on);
        saveData();
        p.sendMessage(Component.text(msg(on ? "enabled" : "disabled"),
                on ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (live.contains(p.getUniqueId())) apply(p, true);
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
        String sub = args.length > 0 ? args[0].toLowerCase() : "toggle";

        if (sub.equals("reload")) {
            if (!sender.hasPermission("livetag.admin")) return true;
            reloadConfig();
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (live.contains(o.getUniqueId())) apply(o, true);
            }
            sender.sendMessage(Component.text("LiveTag reloaded.", NamedTextColor.GREEN));
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
            default -> setLive(p, !live.contains(p.getUniqueId()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        return args.length == 1 ? List.of("menu", "on", "off") : List.of();
    }
}
