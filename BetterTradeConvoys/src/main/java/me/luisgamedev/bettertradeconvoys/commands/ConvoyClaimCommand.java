package me.luisgamedev.bettertradeconvoys.commands;

import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.service.ClaimStore;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class ConvoyClaimCommand {

    private final ClaimStore claimStore;
    private final LanguageManager lang;

    public ConvoyClaimCommand(ClaimStore claimStore, LanguageManager lang) {
        this.claimStore = claimStore;
        this.lang = lang;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(lang.get("errors.ingame_only"));
            return true;
        }
        List<ItemStack> rewards = claimStore.claimAll(p.getUniqueId());
        if (rewards.isEmpty()) {
            p.sendMessage(lang.get("errors.no_claims"));
            return true;
        }
        for (ItemStack it : rewards) {
            var leftover = p.getInventory().addItem(it);
            if (!leftover.isEmpty()) {
                leftover.values().forEach(stack -> p.getWorld().dropItemNaturally(p.getLocation(), stack));
            }
        }
        p.sendMessage(lang.get("info.claimed"));
        return true;
    }
}
