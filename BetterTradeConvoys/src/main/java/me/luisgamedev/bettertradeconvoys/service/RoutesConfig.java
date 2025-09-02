package me.luisgamedev.bettertradeconvoys.service;

import me.luisgamedev.bettertradeconvoys.BetterTradeConvoys;
import me.luisgamedev.bettertradeconvoys.model.RouteDefinition;
import me.luisgamedev.bettertradeconvoys.model.RouteStep;
import me.luisgamedev.bettertradeconvoys.model.TradeDefinition;
import me.luisgamedev.bettertradeconvoys.model.WaypointStep;
import me.luisgamedev.bettertradeconvoys.model.TradeStep;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

/**
 * Liest routes.yml im neuen Schema:
 *
 * routes:
 *   iron_to_diamond:
 *     display-name: "Iron → Diamond Run"
 *     npc-type: VILLAGER
 *     waypoint-world: "world"
 *     speed: 1.15
 *     follow-radius: 24
 *     expire-seconds: 900
 *     trade-delay-seconds: 8
 *     announce-start: true
 *     steps:
 *       - { x: 100.5, y: 64.0, z: -30.5 }
 *       - { x: 120.5, y: 64.0, z: -30.5 }
 *       - trade
 *       - { x: 140.5, y: 64.0, z: -15.5 }
 *     trades:
 *       - input:  { material: IRON_INGOT, amount: 20 }
 *         output: { material: DIAMOND,    amount: 10 }
 *     daily-limit: 3
 *     cooldown-seconds: 3600
 *
 * Fallback (alt): wenn "steps" fehlt, wird "waypoints" als reine Waypoint-Liste verwendet.
 */
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
            String worldName = rs.getString("waypoint-world", "world");

            String npcTypeStr = rs.getString("npc-type", "VILLAGER");
            EntityType npcType;
            try {
                npcType = EntityType.valueOf(npcTypeStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid npc-type '" + npcTypeStr + "' for route '" + id + "'. Using VILLAGER.");
                npcType = EntityType.VILLAGER;
            }

            double speed = rs.getDouble("speed", 1.0);
            double followRadius = rs.getDouble("follow-radius", 24.0);
            int expireSeconds = rs.getInt("expire-seconds", 900);
            int tradeDelaySeconds = rs.getInt("trade-delay-seconds", 0);
            boolean announceStart = rs.getBoolean("announce-start", false);

            // Steps (neu): Mischung aus Map {x,y,z} und String "trade"
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
                        if ("trade".equalsIgnoreCase(s.trim())) {
                            steps.add(TradeStep.INSTANCE);
                        } else {
                            plugin.getLogger().warning("Unknown step string '" + s + "' in route '" + id + "'. Ignored.");
                        }
                    } else {
                        plugin.getLogger().warning("Unsupported step entry type in route '" + id + "': " + (o == null ? "null" : o.getClass().getName()));
                    }
                }
            } else {
                // Fallback: altes Feld "waypoints" -> nur Waypoints
                List<Map<?, ?>> wpList = rs.getMapList("waypoints");
                if (!wpList.isEmpty()) {
                    for (Map<?, ?> map : wpList) {
                        double x = toDouble(map.get("x"), 0.0);
                        double y = toDouble(map.get("y"), 64.0);
                        double z = toDouble(map.get("z"), 0.0);
                        Location loc = new Location(Bukkit.getWorld(worldName), x, y, z);
                        steps.add(new WaypointStep(loc));
                    }
                }
            }

            if (steps.isEmpty()) {
                plugin.getLogger().warning("Route '" + id + "' has no steps/waypoints. Skipping.");
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
                    Material inMat = Material.valueOf(String.valueOf(in.get("material")).toUpperCase(Locale.ROOT));
                    int inAmt = toInt(in.get("amount"), 1);
                    Material outMat = Material.valueOf(String.valueOf(out.get("material")).toUpperCase(Locale.ROOT));
                    int outAmt = toInt(out.get("amount"), 1);
                    trades.add(new TradeDefinition(new ItemStack(inMat, inAmt), new ItemStack(outMat, outAmt)));
                } catch (Exception ex) {
                    plugin.getLogger().warning("Invalid trade materials in route '" + id + "': " + ex.getMessage());
                }
            }

            int limit = rs.getInt("daily-limit", 3);
            int cooldown = rs.getInt("cooldown-seconds", 3600);

            RouteDefinition def = new RouteDefinition(
                    idLower,
                    display,
                    npcType,
                    worldName,
                    steps,
                    trades,
                    limit,
                    cooldown,
                    speed,
                    followRadius,
                    expireSeconds,
                    tradeDelaySeconds,
                    announceStart
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
        try {
            return o == null ? def : Double.parseDouble(o.toString());
        } catch (Exception e) {
            return def;
        }
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? def : Integer.parseInt(o.toString());
        } catch (Exception e) {
            return def;
        }
    }
}
