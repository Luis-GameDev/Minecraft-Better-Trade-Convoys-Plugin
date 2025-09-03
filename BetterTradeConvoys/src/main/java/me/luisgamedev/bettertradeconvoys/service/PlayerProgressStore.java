package me.luisgamedev.bettertradeconvoys.service;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;

public class PlayerProgressStore {

    private final Plugin plugin;
    private File file;
    private YamlConfiguration cfg;

    public PlayerProgressStore(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "progress.yml");
        if (!file.exists()) {
            try { file.getParentFile().mkdirs(); file.createNewFile(); } catch (IOException ignored) {}
        }
        cfg = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try { cfg.save(file); } catch (IOException e) {
            plugin.getLogger().warning("Failed saving progress.yml: " + e.getMessage());
        }
    }

    private String base(UUID player, String route) {
        return "players." + player + "." + route;
    }

    // ======== Cooldown ========
    public long getLastStartMillis(UUID player, String route) {
        return cfg.getLong(base(player, route) + ".lastStartMillis", 0L);
    }

    public void setLastStartMillis(UUID player, String route, long millis) {
        cfg.set(base(player, route) + ".lastStartMillis", millis);
        save();
    }

    // ======== Daily / Weekly / Monthly counts ========
    public int getStartsToday(UUID player, String route) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        String keyDate = base(player, route) + ".daily.date";
        String keyCount = base(player, route) + ".daily.count";
        String stored = cfg.getString(keyDate, "");
        if (!today.toString().equals(stored)) {
            cfg.set(keyDate, today.toString());
            cfg.set(keyCount, 0);
            save();
            return 0;
        }
        return cfg.getInt(keyCount, 0);
    }

    public void incStartsToday(UUID player, String route) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        String keyDate = base(player, route) + ".daily.date";
        String keyCount = base(player, route) + ".daily.count";
        String stored = cfg.getString(keyDate, "");
        if (!today.toString().equals(stored)) {
            cfg.set(keyDate, today.toString());
            cfg.set(keyCount, 0);
        }
        cfg.set(keyCount, cfg.getInt(keyCount, 0) + 1);
        save();
    }

    public int getStartsThisWeek(UUID player, String route) {
        WeekFields wf = WeekFields.ISO;
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        int week = today.get(wf.weekOfWeekBasedYear());
        int year = today.get(wf.weekBasedYear());
        String keyWeek = base(player, route) + ".weekly.week";
        String keyYear = base(player, route) + ".weekly.year";
        String keyCount = base(player, route) + ".weekly.count";

        int sWeek = cfg.getInt(keyWeek, -1);
        int sYear = cfg.getInt(keyYear, -1);
        if (sWeek != week || sYear != year) {
            cfg.set(keyWeek, week);
            cfg.set(keyYear, year);
            cfg.set(keyCount, 0);
            save();
            return 0;
        }
        return cfg.getInt(keyCount, 0);
    }

    public void incStartsThisWeek(UUID player, String route) {
        WeekFields wf = WeekFields.ISO;
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        int week = today.get(wf.weekOfWeekBasedYear());
        int year = today.get(wf.weekBasedYear());
        String keyWeek = base(player, route) + ".weekly.week";
        String keyYear = base(player, route) + ".weekly.year";
        String keyCount = base(player, route) + ".weekly.count";

        int sWeek = cfg.getInt(keyWeek, -1);
        int sYear = cfg.getInt(keyYear, -1);
        if (sWeek != week || sYear != year) {
            cfg.set(keyWeek, week);
            cfg.set(keyYear, year);
            cfg.set(keyCount, 0);
        }
        cfg.set(keyCount, cfg.getInt(keyCount, 0) + 1);
        save();
    }

    public int getStartsThisMonth(UUID player, String route) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        int y = today.getYear();
        int m = today.getMonthValue();
        String keyY = base(player, route) + ".monthly.year";
        String keyM = base(player, route) + ".monthly.month";
        String keyCount = base(player, route) + ".monthly.count";

        int sY = cfg.getInt(keyY, -1);
        int sM = cfg.getInt(keyM, -1);
        if (sY != y || sM != m) {
            cfg.set(keyY, y);
            cfg.set(keyM, m);
            cfg.set(keyCount, 0);
            save();
            return 0;
        }
        return cfg.getInt(keyCount, 0);
    }

    public void incStartsThisMonth(UUID player, String route) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        int y = today.getYear();
        int m = today.getMonthValue();
        String keyY = base(player, route) + ".monthly.year";
        String keyM = base(player, route) + ".monthly.month";
        String keyCount = base(player, route) + ".monthly.count";

        int sY = cfg.getInt(keyY, -1);
        int sM = cfg.getInt(keyM, -1);
        if (sY != y || sM != m) {
            cfg.set(keyY, y);
            cfg.set(keyM, m);
            cfg.set(keyCount, 0);
        }
        cfg.set(keyCount, cfg.getInt(keyCount, 0) + 1);
        save();
    }
}
