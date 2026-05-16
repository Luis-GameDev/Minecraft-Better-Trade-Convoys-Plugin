package me.luisgamedev.bettertradeconvoys.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.luisgamedev.bettertradeconvoys.BetterTradeConvoys;
import me.luisgamedev.bettertradeconvoys.model.ConvoyInstance;
import me.luisgamedev.bettertradeconvoys.model.RouteDefinition;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class BetterTradeConvoysExpansion extends PlaceholderExpansion {

    private final BetterTradeConvoys plugin;

    public BetterTradeConvoysExpansion(BetterTradeConvoys plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "bettertradeconvoys"; }
    @Override public @NotNull String getAuthor() { return "Luis-GameDev"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        String[] parts = params.split("_", 2);
        if (parts.length < 2) return "";
        String key = parts[0];
        String routeId = parts[1];

        RouteDefinition route = plugin.routes().getRoute(routeId);
        if (route == null) return "0";
        UUID uuid = player.getUniqueId();

        long now = System.currentTimeMillis();
        long last = plugin.progress().getLastStartMillis(uuid, routeId);
        long cooldownLeft = Math.max(0L, route.cooldownSeconds() - ((now - last) / 1000));

        int dailyUsed = plugin.progress().getStartsToday(uuid, routeId);
        int weeklyUsed = plugin.progress().getStartsThisWeek(uuid, routeId);
        int monthlyUsed = plugin.progress().getStartsThisMonth(uuid, routeId);

        return switch (key) {
            case "cooldown" -> String.valueOf(cooldownLeft);
            case "dailyused" -> String.valueOf(dailyUsed);
            case "weeklyused" -> String.valueOf(weeklyUsed);
            case "monthlyused" -> String.valueOf(monthlyUsed);
            case "dailyleft" -> left(route.dailyLimit(), dailyUsed);
            case "weeklyleft" -> left(route.weeklyLimit(), weeklyUsed);
            case "monthlyleft" -> left(route.monthlyLimit(), monthlyUsed);
            case "dailylimit" -> String.valueOf(route.dailyLimit());
            case "weeklylimit" -> String.valueOf(route.weeklyLimit());
            case "monthlylimit" -> String.valueOf(route.monthlyLimit());
            default -> "";
        };
    }

    private String left(int limit, int used) {
        if (limit < 0) return "-1";
        return String.valueOf(Math.max(0, limit - used));
    }
}
