package me.luisgamedev.bettertradeconvoys.model;

import java.util.List;
import java.util.Set;
import java.util.Collections;
import org.bukkit.Material;

public record RouteDefinition(
        String id,
        String displayName,
        String description,
        String worldName,
        List<RouteStep> steps,
        List<TradeDefinition> trades,
        int dailyLimit,
        int weeklyLimit,
        int monthlyLimit,
        int cooldownSeconds,
        double speed,
        double followRadius,
        int expireSeconds,
        int tradeDelaySeconds,
        boolean announceStart,
        String guiLayout,
        Material guiItemMaterial,
        Integer guiCustomModelData,
        String guiItemModel,
        String guiItemName,
        List<String> guiItemLore,
        Set<Integer> npcIds
) {
    public List<String> guiItemLore() {
        return guiItemLore == null ? Collections.emptyList() : guiItemLore;
    }
}
