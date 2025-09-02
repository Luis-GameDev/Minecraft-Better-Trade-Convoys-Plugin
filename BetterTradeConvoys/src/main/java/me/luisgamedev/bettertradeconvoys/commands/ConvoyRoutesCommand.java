package me.luisgamedev.bettertradeconvoys.commands;

import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.model.RouteDefinition;
import me.luisgamedev.bettertradeconvoys.service.RoutesConfig;
import org.bukkit.command.CommandSender;

public class ConvoyRoutesCommand {

    private final RoutesConfig routes;
    private final LanguageManager lang;

    public ConvoyRoutesCommand(RoutesConfig routes, LanguageManager lang) {
        this.routes = routes;
        this.lang = lang;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (routes.getAll().isEmpty()) {
            sender.sendMessage(lang.getRaw("info.routes_header"));
            return true;
        }
        sender.sendMessage(lang.getRaw("info.routes_header"));
        for (var e : routes.getAll().entrySet()) {
            String id = e.getKey();
            RouteDefinition r = e.getValue();
            sender.sendMessage(
                    lang.formatRaw("info.route_entry",
                            lang.p("id", id, "name", r.displayName(),
                                    "limit", r.dailyLimit(), "cooldown", r.cooldownSeconds()))
            );
        }
        return true;
    }
}
