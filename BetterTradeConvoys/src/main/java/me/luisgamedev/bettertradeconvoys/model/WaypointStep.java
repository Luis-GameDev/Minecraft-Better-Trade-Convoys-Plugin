package me.luisgamedev.bettertradeconvoys.model;

import org.bukkit.Location;

public final class WaypointStep implements RouteStep {
    private final Location loc;
    private final String message;

    /**
     * Creates a waypoint step without a message.
     */
    public WaypointStep(Location loc) {
        this(loc, null);
    }

    public WaypointStep(Location loc, String message) {
        this.loc = loc;
        this.message = message;
    }

    public Location getLoc() {
        return loc;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
