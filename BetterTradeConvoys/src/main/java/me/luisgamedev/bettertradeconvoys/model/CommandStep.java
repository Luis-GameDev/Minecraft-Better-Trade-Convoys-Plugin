package me.luisgamedev.bettertradeconvoys.model;

public final class CommandStep implements RouteStep {

    private final String command;
    private final boolean asConsole;
    private final String message;

    public CommandStep(String command, boolean asConsole, String message) {
        this.command = command;
        this.asConsole = asConsole;
        this.message = message;
    }

    public String getCommand() {
        return command;
    }

    public boolean isAsConsole() {
        return asConsole;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
