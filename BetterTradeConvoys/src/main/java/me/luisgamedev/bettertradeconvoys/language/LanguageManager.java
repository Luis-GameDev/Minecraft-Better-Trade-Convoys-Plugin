package me.luisgamedev.bettertradeconvoys.language;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class LanguageManager {

    private final Plugin plugin;
    private YamlConfiguration cfg;
    private String prefix = "";
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacyAmp = LegacyComponentSerializer.legacyAmpersand();
    private final LegacyComponentSerializer legacySection = LegacyComponentSerializer.legacySection();

    public LanguageManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File f = new File(plugin.getDataFolder(), "language.yml");
        if (!f.exists()) {
            plugin.saveResource("language.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(f);
        prefix = toLegacy(cfg.getString("prefix", "&6[BetterTradeConvoys]&r "));
    }

    public String get(String path) {
        return get(null, path);
    }

    public String get(OfflinePlayer player, String path) {
        String v = cfg.getString(path);
        if (v == null) return prefix + "§cMissing lang key: " + path;
        return prefix + parseToLegacy(player, v);
    }

    public String getRaw(String path) {
        return getRaw(null, path);
    }

    public String getRaw(OfflinePlayer player, String path) {
        String v = cfg.getString(path);
        if (v == null) return "Missing lang key: " + path;
        return parseToLegacy(player, v);
    }

    public String format(String path, Map<String, Object> placeholders) {
        return format(null, path, placeholders);
    }

    public String format(OfflinePlayer player, String path, Map<String, Object> placeholders) {
        String base = cfg.getString(path);
        if (base == null) base = "Missing lang key: " + path;
        String withVars = replacePlaceholders(base, placeholders);
        return prefix + parseToLegacy(player, withVars);
    }

    public String formatRaw(String path, Map<String, Object> placeholders) {
        return formatRaw(null, path, placeholders);
    }

    public String formatRaw(OfflinePlayer player, String path, Map<String, Object> placeholders) {
        String base = cfg.getString(path);
        if (base == null) base = "Missing lang key: " + path;
        return parseToLegacy(player, replacePlaceholders(base, placeholders));
    }

    private String applyPlaceholders(OfflinePlayer player, String s) {
        if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) return s;
        try {
            return PlaceholderAPI.setPlaceholders(player, s);
        } catch (Throwable ignored) {
            return s;
        }
    }

    public String parseToLegacy(OfflinePlayer player, String s) {
        String parsed = s == null ? "" : s;
        parsed = applyPlaceholders(player, parsed);
        return toLegacy(parsed);
    }

    public String parseToLegacy(String s) {
        return parseToLegacy(null, s);
    }

    public Component parseToComponent(OfflinePlayer player, String s) {
        String parsed = s == null ? "" : s;
        parsed = applyPlaceholders(player, parsed);
        return parseFormatting(parsed);
    }

    private String toLegacy(String s) {
        if (s == null) return "";
        return legacySection.serialize(parseFormatting(s));
    }

    private Component parseFormatting(String s) {
        String normalized = s.replace('§', '&');
        try {
            Component miniParsed = miniMessage.deserialize(normalized);
            String legacy = legacySection.serialize(miniParsed).replace('§', '&');
            return legacyAmp.deserialize(legacy);
        } catch (RuntimeException ignored) {
            return legacyAmp.deserialize(normalized);
        }
    }

    private String replacePlaceholders(String s, Map<String, Object> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return s;
        String out = s;
        for (Map.Entry<String, Object> e : placeholders.entrySet()) {
            String key = "\\{" + e.getKey() + "\\}";
            out = out.replaceAll(key, e.getValue() == null ? "" : e.getValue().toString());
        }
        return out;
    }

    public Map<String, Object> p(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
