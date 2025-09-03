package me.luisgamedev.bettertradeconvoys.model;

/**
 * Special step that represents a trading phase.
 */
public final class TradeStep implements RouteStep {
    /**
     * Shared instance used when no message is configured.
     */
    public static final TradeStep INSTANCE = new TradeStep(null);

    private final String message;

    public TradeStep(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
