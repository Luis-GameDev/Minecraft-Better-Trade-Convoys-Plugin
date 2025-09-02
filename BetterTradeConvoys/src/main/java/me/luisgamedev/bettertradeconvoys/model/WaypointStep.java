package me.luisgamedev.bettertradeconvoys.model;

import org.bukkit.Location;

public final class WaypointStep implements RouteStep {
    private final Location loc;

    public WaypointStep(Location loc) {
        this.loc = loc;
    }

    public Location getLoc() {
        return loc;
    }
}
