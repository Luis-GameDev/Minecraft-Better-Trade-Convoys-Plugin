package me.luisgamedev.bettertradeconvoys.service;

import me.luisgamedev.bettertradeconvoys.BetterTradeConvoys;
import me.luisgamedev.bettertradeconvoys.model.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

public class RoutesConfig {

    private final BetterTradeConvoys plugin;
    private final Map<String, RouteDefinition> routes = new LinkedHashMap<>();

    public RoutesConfig(BetterTradeConvoys plugin) {
        this.plugin = plugin;
    }

    public void load() {
        routes.clear();
        File f = new File(plugin.getDataFolder(), "routes.yml");
        if (!f.exists()) {
            plugin.saveResource("routes.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = cfg.getConfigurationSection("routes");
        if (root == null) {
            plugin.getLogger().warning("No 'routes' section found in routes.yml");
            return;
        }

        for (String id : root.getKeys(false)) {
            ConfigurationSection rs = root.getConfigurationSection(id);
            if (rs == null) continue;

            String idLower = id.toLowerCase(Locale.ROOT);
            String display = rs.getString("display-name", id);
            String description = rs.getString("description", id);
            String worldName = rs.getString("waypoint-world", "world");

            double speed = rs.getDouble("speed", 1.0);
            double followRadius = rs.getDouble("follow-radius", 24.0);
            int expireSeconds = rs.getInt("expire-seconds", 900);
            int tradeDelaySeconds = rs.getInt("trade-delay-seconds", 0);
            boolean announceStart = rs.getBoolean("announce-start", false);

            // Steps
            List<RouteStep> steps = new ArrayList<>();
            List<?> rawSteps = rs.getList("steps");
            if (rawSteps != null && !rawSteps.isEmpty()) {
                for (Object o : rawSteps) {
                    if (o instanceof Map<?, ?> map) {
                        double x = toDouble(map.get("x"), 0.0);
                        double y = toDouble(map.get("y"), 64.0);
                        double z = toDouble(map.get("z"), 0.0);
                        Location loc = new Location(Bukkit.getWorld(worldName), x, y, z);
                        steps.add(new WaypointStep(loc));
                    } else if (o instanceof String s) {
                        if ("trade".equalsIgnoreCase(s.trim())) steps.add(TradeStep.INSTANCE);
                        else plugin.getLogger().warning("Unknown step string '" + s + "' in route '" + id + "'. Ignored.");
                    } else {
                        plugin.getLogger().warning("Unsupported step entry in route '" + id + "': " + (o == null ? "null" : o.getClass().getName()));
                    }
                }
            } else {
                plugin.getLogger().warning("Route '" + id + "' has no steps. Skipping.");
                continue;
            }

            // Trades
            List<TradeDefinition> trades = new ArrayList<>();
            List<Map<?, ?>> tradeList = rs.getMapList("trades");
            for (Map<?, ?> m : tradeList) {
                Object inObj = m.get("input");
                Object outObj = m.get("output");
                if (!(inObj instanceof Map<?, ?> in) || !(outObj instanceof Map<?, ?> out)) {
                    plugin.getLogger().warning("Invalid trade entry in route '" + id + "'. Skipping this trade.");
                    continue;
                }
                try {
                    var inMat = Material.valueOf(String.valueOf(in.get("material")).toUpperCase(Locale.ROOT));
                    int inAmt = toInt(in.get("amount"), 1);
                    var outMat = Material.valueOf(String.valueOf(out.get("material")).toUpperCase(Locale.ROOT));
                    int outAmt = toInt(out.get("amount"), 1);
                    trades.add(new TradeDefinition(new ItemStack(inMat, inAmt), new ItemStack(outMat, outAmt)));
                } catch (Exception ex) {
                    plugin.getLogger().warning("Invalid trade materials in route '" + id + "': " + ex.getMessage());
                }
            }

            int dailyLimit = rs.getInt("daily-limit", 0);
            int weeklyLimit = rs.getInt("weekly-limit", 0);
            int monthlyLimit = rs.getInt("monthly-limit", 0);
            int cooldown = rs.getInt("cooldown-seconds", 0);

            // npc-ids
            Set<Integer> npcIds = new HashSet<>();
            for (Object o : rs.getList("npc-ids", Collections.emptyList())) {
                try { npcIds.add(Integer.parseInt(String.valueOf(o))); } catch (NumberFormatException ignored) {}
            }
            if (npcIds.isEmpty()) {
                plugin.getLogger().warning("Route '" + id + "' has no npc-ids. It will not be offered by any NPC.");
            }

            RouteDefinition def = new RouteDefinition(
                    idLower,
                    display,
                    description,
                    worldName,
                    steps,
                    trades,
                    dailyLimit,
                    weeklyLimit,
                    monthlyLimit,
                    cooldown,
                    speed,
                    followRadius,
                    expireSeconds,
                    tradeDelaySeconds,
                    announceStart,
                    npcIds
            );

            routes.put(def.id(), def);
        }

        plugin.getLogger().info("Loaded " + routes.size() + " trade routes.");
    }

    public RouteDefinition getRoute(String id) {
        if (id == null) return null;
        return routes.get(id.toLowerCase(Locale.ROOT));
    }

    public Map<String, RouteDefinition> getAll() {
        return Collections.unmodifiableMap(routes);
    }

    private static double toDouble(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? def : Double.parseDouble(o.toString()); } catch (Exception e) { return def; }
    }
    private static int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? def : Integer.parseInt(o.toString()); } catch (Exception e) { return def; }
    }
}
