package me.luisgamedev.bettertradeconvoys.language;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

public class LanguageManager {

    private final Plugin plugin;
    private YamlConfiguration cfg;
    private String prefix = "";

    public LanguageManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File f = new File(plugin.getDataFolder(), "language.yml");
        if (!f.exists()) {
            plugin.saveResource("language.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(f);
        prefix = colorize(cfg.getString("prefix", "&6[BetterTradeConvoys]&r "));
    }

    public String get(String path) {
        String v = cfg.getString(path);
        if (v == null) return prefix + ChatColor.RED + "Missing lang key: " + path;
        return prefix + colorize(v);
    }

    public String getRaw(String path) {
        String v = cfg.getString(path);
        if (v == null) return "Missing lang key: " + path;
        return colorize(v);
    }

    public String format(String path, Map<String, Object> placeholders) {
        String base = cfg.getString(path);
        if (base == null) base = "Missing lang key: " + path;
        String colored = colorize(base);
        String withVars = replacePlaceholders(colored, placeholders);
        return prefix + withVars;
    }

    public String formatRaw(String path, Map<String, Object> placeholders) {
        String base = cfg.getString(path);
        if (base == null) base = "Missing lang key: " + path;
        String colored = colorize(base);
        return replacePlaceholders(colored, placeholders);
    }

    private String colorize(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
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
