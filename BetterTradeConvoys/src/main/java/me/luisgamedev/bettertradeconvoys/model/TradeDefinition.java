package me.luisgamedev.bettertradeconvoys.model;

import org.bukkit.inventory.ItemStack;

public record TradeDefinition(
        ItemStack input,
        ItemStack output
) { }
