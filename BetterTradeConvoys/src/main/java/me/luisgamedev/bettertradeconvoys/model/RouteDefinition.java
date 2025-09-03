package me.luisgamedev.bettertradeconvoys.model;

import java.util.List;
import java.util.Set;

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
        Set<Integer> npcIds
) { }
