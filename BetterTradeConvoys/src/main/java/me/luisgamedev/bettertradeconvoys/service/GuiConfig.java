package me.luisgamedev.bettertradeconvoys.service;

import me.luisgamedev.bettertradeconvoys.BetterTradeConvoys;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class GuiConfig {
    private final BetterTradeConvoys plugin;

    private String defaultLayout = "default";
    private final java.util.Map<String, GuiLayout> layouts = new java.util.HashMap<>();

    public GuiConfig(BetterTradeConvoys plugin) {
        this.plugin = plugin;
    }

    public void load() {
        layouts.clear();
        File f = new File(plugin.getDataFolder(), "gui.yml");
        if (!f.exists()) {
            plugin.saveResource("gui.yml", false);
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        defaultLayout = cfg.getString("default-layout", "default").toLowerCase(Locale.ROOT);

        ConfigurationSection sec = cfg.getConfigurationSection("layouts");
        if (sec == null) {
            plugin.getLogger().warning("No 'layouts' section found in gui.yml");
            return;
        }

        for (String key : sec.getKeys(false)) {
            ConfigurationSection ls = sec.getConfigurationSection(key);
            if (ls == null) continue;
            String id = key.toLowerCase(Locale.ROOT);
            String title = color(ls.getString("title", "Routes"));
            int size = normalizeSize(ls.getInt("size", 54));

            ItemStack border = parseGuiItem(ls.getConfigurationSection("border-item"), Material.GRAY_STAINED_GLASS_PANE, " ", Collections.emptyList());
            ItemStack prev = parseGuiItem(ls.getConfigurationSection("prev-item"), Material.ARROW, "&ePrev", Collections.singletonList("&7Page {page}"));
            ItemStack next = parseGuiItem(ls.getConfigurationSection("next-item"), Material.ARROW, "&eNext", Collections.singletonList("&7Page {page}"));
            List<String> pattern = ls.getStringList("layout-pattern");
            List<Integer> borderSlots = new ArrayList<>();
            List<Integer> routeSlots = new ArrayList<>();
            List<Integer> prevSlots = new ArrayList<>();
            List<Integer> nextSlots = new ArrayList<>();
            if (pattern != null && !pattern.isEmpty()) {
                size = normalizeSize(pattern.size() * 9);
                ConfigurationSection slotTypes = ls.getConfigurationSection("slot-types");
                for (int row = 0; row < pattern.size(); row++) {
                    String line = pattern.get(row);
                    for (int col = 0; col < Math.min(9, line.length()); col++) {
                        char patternKey = line.charAt(col);
                        int slot = row * 9 + col;
                        String type = slotTypes != null ? slotTypes.getString(String.valueOf(patternKey), "") : "";
                        if ("BORDER".equalsIgnoreCase(type) || patternKey == '1') borderSlots.add(slot);
                        else if ("ROUTE".equalsIgnoreCase(type) || patternKey == '0') routeSlots.add(slot);
                        else if ("PREV".equalsIgnoreCase(type) || patternKey == '2') prevSlots.add(slot);
                        else if ("NEXT".equalsIgnoreCase(type) || patternKey == '3') nextSlots.add(slot);
                    }
                }
            } else {
                int prevSlot = clampSlot(ls.getInt("prev-slot", 37), size);
                int nextSlot = clampSlot(ls.getInt("next-slot", 43), size);
                prevSlots.add(prevSlot);
                nextSlots.add(nextSlot);
                routeSlots = ls.getIntegerList("route-slots");
                if (routeSlots == null || routeSlots.isEmpty()) routeSlots = defaultRouteSlots(size, prevSlot, nextSlot);
            }

            layouts.put(id, new GuiLayout(id, title, size, border, prev, next, routeSlots, borderSlots, prevSlots, nextSlots));
        }

        if (!layouts.containsKey(defaultLayout) && !layouts.isEmpty()) {
            defaultLayout = layouts.keySet().iterator().next();
        }
    }

    public GuiLayout getLayoutOrDefault(String id) {
        if (layouts.isEmpty()) return null;
        if (id != null && layouts.containsKey(id.toLowerCase(Locale.ROOT))) return layouts.get(id.toLowerCase(Locale.ROOT));
        return layouts.get(defaultLayout);
    }

    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }

    private static int normalizeSize(int size) {
        int s = Math.max(9, Math.min(54, size));
        return (s / 9) * 9;
    }

    private static int clampSlot(int slot, int size) {
        if (slot < 0) return 0;
        if (slot >= size) return size - 1;
        return slot;
    }

    private static List<Integer> defaultRouteSlots(int size, int prevSlot, int nextSlot) {
        List<Integer> slots = new ArrayList<>();
        int rows = size / 9;
        for (int row = 1; row < rows - 1; row++) {
            for (int col = 1; col <= 7; col++) {
                int slot = row * 9 + col;
                if (slot >= size || slot == prevSlot || slot == nextSlot) continue;
                slots.add(slot);
            }
        }
        return slots;
    }

    private ItemStack parseGuiItem(ConfigurationSection sec, Material fallbackMat, String fallbackName, List<String> fallbackLore) {
        Material mat = fallbackMat;
        String name = fallbackName;
        List<String> lore = new ArrayList<>(fallbackLore);
        if (sec != null) {
            String materialName = sec.getString("material", fallbackMat.name());
            try { mat = Material.valueOf(materialName.toUpperCase(Locale.ROOT)); } catch (Exception ignored) {}
            name = sec.getString("name", fallbackName);
            lore = sec.getStringList("lore");
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> translatedLore = new ArrayList<>();
            for (String line : lore) translatedLore.add(color(line));
            meta.setLore(translatedLore);
            applyCustomModelData(sec, meta);
            applyItemModel(sec, meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void applyCustomModelData(ConfigurationSection sec, ItemMeta meta) {
        if (sec == null || !sec.contains("custom-model-data")) return;
        int customModelData = sec.getInt("custom-model-data");
        meta.setCustomModelData(customModelData);
    }

    private void applyItemModel(ConfigurationSection sec, ItemMeta meta) {
        if (sec == null || !sec.contains("item-model")) return;
        String rawItemModel = sec.getString("item-model");
        if (rawItemModel == null || rawItemModel.isBlank()) return;
        NamespacedKey itemModel = NamespacedKey.fromString(rawItemModel.trim());
        if (itemModel == null) {
            plugin.getLogger().warning("Invalid item-model key in gui.yml: " + rawItemModel);
            return;
        }

        try {
            Method setItemModel = meta.getClass().getMethod("setItemModel", NamespacedKey.class);
            setItemModel.invoke(meta, itemModel);
        } catch (NoSuchMethodException ignored) {
            // Not available on this server version.
        } catch (Exception e) {
            plugin.getLogger().warning("Could not apply item-model '" + rawItemModel + "' from gui.yml: " + e.getMessage());
        }
    }

    public record GuiLayout(String id, String title, int size, ItemStack borderItem,
                            ItemStack prevItem, ItemStack nextItem, List<Integer> routeSlots,
                            List<Integer> borderSlots, List<Integer> prevSlots, List<Integer> nextSlots) {}
}
