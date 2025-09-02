package me.luisgamedev.bettertradeconvoys.service;

import me.luisgamedev.bettertradeconvoys.BetterTradeConvoys;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.serialization.ConfigurationSerialization;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ClaimStore {

    static {
        // Bukkit ItemStack ist serializable per ConfigurationSerialization
        ConfigurationSerialization.registerClass(ItemStack.class);
    }

    private final BetterTradeConvoys plugin;
    private final File file;
    private final YamlConfiguration cfg;

    public ClaimStore(BetterTradeConvoys plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "claims.yml");
        this.cfg = new YamlConfiguration();
    }

    public void load() {
        try {
            if (!file.exists()) file.getParentFile().mkdirs();
            if (file.exists()) cfg.load(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load claims.yml: " + e.getMessage());
        }
    }

    public void save() {
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save claims.yml: " + e.getMessage());
        }
    }

    public void add(UUID player, List<ItemStack> items) {
        List<ItemStack> existing = get(player);
        existing.addAll(items);
        cfg.set(player.toString(), existing);
        save();
    }

    @SuppressWarnings("unchecked")
    public List<ItemStack> get(UUID player) {
        List<?> raw = cfg.getList(player.toString());
        if (raw == null) return new ArrayList<>();
        List<ItemStack> out = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof ItemStack it) out.add(it);
        }
        return out;
    }

    public List<ItemStack> claimAll(UUID player) {
        List<ItemStack> items = get(player);
        cfg.set(player.toString(), null);
        save();
        return items;
    }
}
