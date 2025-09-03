package me.luisgamedev.bettertradeconvoys.commands;

import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.model.RouteDefinition;
import me.luisgamedev.bettertradeconvoys.model.TradeDefinition;
import me.luisgamedev.bettertradeconvoys.service.ConvoyManager;
import me.luisgamedev.bettertradeconvoys.service.RoutesConfig;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class ConvoyStartCommand {

    private final ConvoyManager convoyManager;
    private final RoutesConfig routesConfig;
    private final LanguageManager lang;

    public ConvoyStartCommand(ConvoyManager convoyManager, RoutesConfig routesConfig, LanguageManager lang) {
        this.convoyManager = convoyManager;
        this.routesConfig = routesConfig;
        this.lang = lang;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(lang.get("errors.ingame_only"));
            return true;
        }
        if (!p.hasPermission("bettertradeconvoys.use")) {
            p.sendMessage(lang.get("errors.no_permission"));
            return true;
        }
        if (args.length < 2) {
            p.sendMessage(lang.getRaw("info.routes_header"));
            return true;
        }
        String routeId = args[1].toLowerCase();
        RouteDefinition route = routesConfig.getRoute(routeId);
        if (route == null) {
            p.sendMessage(lang.format("errors.unknown_route", lang.p("route", routeId)));
            return true;
        }
        if (!p.hasPermission("bettertradeconvoys.routes." + routeId)) {
            p.sendMessage(lang.format("errors.route_locked_permission", lang.p("route", routeId)));
            return true;
        }

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            p.sendMessage(lang.get("errors.invalid_hand_item"));
            return true;
        }

        TradeDefinition matched = null;
        for (TradeDefinition t : route.trades()) {
            if (t.input().getType() == hand.getType() && t.input().getAmount() == hand.getAmount()) {
                matched = t;
                break;
            }
        }
        if (matched == null) {
            p.sendMessage(lang.get("errors.no_matching_trade"));
            return true;
        }

        Entity target = p.getTargetEntity(5);
        NPC npc = target == null ? null : CitizensAPI.getNPCRegistry().getNPC(target);
        if (npc == null) {
            p.sendMessage("§cYou must look at an NPC to start a convoy.");
            return true;
        }

        String result = convoyManager.startConvoy(p, npc, routeId, matched);
        p.sendMessage(result);
        return true;
    }
}
