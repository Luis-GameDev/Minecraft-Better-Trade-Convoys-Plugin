package me.luisgamedev.bettertradeconvoys.service;

import me.luisgamedev.bettertradeconvoys.BetterTradeConvoys;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

public class PlayerProgressStore {

    private final BetterTradeConvoys plugin;
    private final File file;
    private final YamlConfiguration cfg;

    public PlayerProgressStore(BetterTradeConvoys plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "progress.yml");
        this.cfg = new YamlConfiguration();
    }

    public void load() {
        try {
            if (!file.exists()) file.getParentFile().mkdirs();
            if (file.exists()) cfg.load(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load progress.yml: " + e.getMessage());
        }
    }

    public void save() {
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save progress.yml: " + e.getMessage());
        }
    }

    private String todayKey() {
        return LocalDate.now().toString();
    }

    public int getStartsToday(UUID player, String routeId) {
        return cfg.getInt(player + "." + todayKey() + ".routes." + routeId + ".count", 0);
    }

    public void incStartsToday(UUID player, String routeId) {
        String base = player + "." + todayKey() + ".routes." + routeId;
        int c = cfg.getInt(base + ".count", 0);
        cfg.set(base + ".count", c + 1);
        save();
    }

    public long getLastStartMillis(UUID player, String routeId) {
        return cfg.getLong(player + ".meta.lastStart." + routeId, 0L);
    }

    public void setLastStartMillis(UUID player, String routeId, long millis) {
        cfg.set(player + ".meta.lastStart." + routeId, millis);
        save();
    }
}
