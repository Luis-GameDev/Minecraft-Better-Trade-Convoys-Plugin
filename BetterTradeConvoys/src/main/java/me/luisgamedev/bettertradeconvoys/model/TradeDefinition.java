package me.luisgamedev.bettertradeconvoys.model;

import org.bukkit.inventory.ItemStack;

public record TradeDefinition(
        ItemStack inputItem,
        ItemStack outputItem,
        double inputMoney,
        double outputMoney
) {
}
