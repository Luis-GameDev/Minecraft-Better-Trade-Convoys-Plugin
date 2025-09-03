package me.luisgamedev.bettertradeconvoys.model;

/**
 * Represents a step in a trade route. Each step may optionally provide a
 * message that will be sent to the route owner once the NPC reaches the step.
 */
public interface RouteStep {
    /**
     * Optional message that should be sent to the owner when this step is
     * reached. Implementations may return {@code null} to indicate that no
     * message should be sent.
     */
    String getMessage();
}
