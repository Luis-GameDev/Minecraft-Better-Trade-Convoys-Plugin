package me.luisgamedev.bettertradeconvoys;

import me.luisgamedev.bettertradeconvoys.commands.ConvoyCommand;
import me.luisgamedev.bettertradeconvoys.listeners.CitizensListeners;
import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.service.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class BetterTradeConvoys extends JavaPlugin {

    private static BetterTradeConvoys instance;

    private RoutesConfig routesConfig;
    private PlayerProgressStore progressStore;
    private ClaimStore claimStore;
    private ConvoyManager convoyManager;
    private LanguageManager language;

    @Override
    public void onEnable() {
        instance = this;

        saveResource("routes.yml", false);
        saveResource("language.yml", false);

        language = new LanguageManager(this);
        language.load();

        routesConfig = new RoutesConfig(this);
        routesConfig.load();

        progressStore = new PlayerProgressStore(this);
        progressStore.load();

        claimStore = new ClaimStore(this);
        claimStore.load();

        convoyManager = new ConvoyManager(this, routesConfig, progressStore, claimStore, language);
        convoyManager.initCitizensCheck();

        getCommand("convoy").setExecutor(new ConvoyCommand(this, convoyManager, routesConfig, claimStore, language));

        Bukkit.getPluginManager().registerEvents(new CitizensListeners(convoyManager, language), this);

        getLogger().info("BetterTradeConvoys enabled.");
    }

    @Override
    public void onDisable() {
        if (convoyManager != null) {
            convoyManager.failAllActiveOnShutdown();
        }
        if (progressStore != null) progressStore.save();
        if (claimStore != null) claimStore.save();
        getLogger().info("BetterTradeConvoys disabled.");
    }

    public static BetterTradeConvoys getInstance() { return instance; }

    public LanguageManager lang() { return language; }
}
