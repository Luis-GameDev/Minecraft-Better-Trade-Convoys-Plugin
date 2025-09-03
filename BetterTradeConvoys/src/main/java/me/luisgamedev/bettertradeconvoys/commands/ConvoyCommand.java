package me.luisgamedev.bettertradeconvoys.commands;

import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.service.ConvoyManager;
import me.luisgamedev.bettertradeconvoys.service.RoutesConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class ConvoyCommand implements CommandExecutor {

    private final ConvoyStartCommand startCommand;
    private final ConvoyRoutesCommand routesCommand;

    public ConvoyCommand(Object plugin, ConvoyManager convoyManager, RoutesConfig routesConfig, LanguageManager lang) {
        this.startCommand = new ConvoyStartCommand(convoyManager, routesConfig, lang);
        this.routesCommand = new ConvoyRoutesCommand(routesConfig, lang);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e/convoy start <routeId>");
            sender.sendMessage("§e/convoy routes");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start":
                return startCommand.handle(sender, args);
            case "routes":
                return routesCommand.handle(sender, args);
            default:
                sender.sendMessage("§e/convoy <start|routes>");
                return true;
        }
    }
}
