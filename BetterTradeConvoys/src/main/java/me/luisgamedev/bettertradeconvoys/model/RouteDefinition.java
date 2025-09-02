package me.luisgamedev.bettertradeconvoys.model;

import org.bukkit.entity.EntityType;

import java.util.List;

public record RouteDefinition(
        String id,
        String displayName,
        EntityType npcType,
        String worldName,
        List<RouteStep> steps,          // <-- neu statt waypoints
        List<TradeDefinition> trades,
        int dailyLimit,
        int cooldownSeconds,
        double speed,                   // citizens speedModifier (z.B. 1.15)
        double followRadius,            // Distanz in Blöcken
        int expireSeconds,              // maximale Laufzeit
        int tradeDelaySeconds,          // Wartezeit bei "trade"-Stops
        boolean announceStart           // global announce beim Start
) { }
