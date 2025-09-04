package me.luisgamedev.bettertradeconvoys.model;


public final class TradeStep implements RouteStep {

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
