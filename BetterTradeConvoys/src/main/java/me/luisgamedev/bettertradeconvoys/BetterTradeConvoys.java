package me.luisgamedev.bettertradeconvoys;

import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.listeners.CitizensListeners;
import me.luisgamedev.bettertradeconvoys.listeners.DepositListener;
import me.luisgamedev.bettertradeconvoys.listeners.RoutesGuiListener;
import me.luisgamedev.bettertradeconvoys.service.ClaimStore;
import me.luisgamedev.bettertradeconvoys.service.ConvoyManager;
import me.luisgamedev.bettertradeconvoys.service.PlayerProgressStore;
import me.luisgamedev.bettertradeconvoys.service.RoutesConfig;
import me.luisgamedev.bettertradeconvoys.commands.ConvoyCommand; // falls dein Command woanders liegt, passe das an
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class BetterTradeConvoys extends JavaPlugin {

    private LanguageManager language;
    private RoutesConfig routesConfig;
    private PlayerProgressStore progressStore;
    private ClaimStore claimStore;
    private ConvoyManager convoyManager;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        saveDefaultIfMissing("routes.yml");
        saveDefaultIfMissing("language.yml");

        // Manager/Stores laden
        language = new LanguageManager(this);
        language.load();
        routesConfig = new RoutesConfig(this); routesConfig.load();
        progressStore = new PlayerProgressStore(this); progressStore.load();
        claimStore = new ClaimStore(this);
        try {
            ClaimStore.class.getMethod("load");
            claimStore.load();
        } catch (NoSuchMethodException ignored) {}

        convoyManager = new ConvoyManager(this, routesConfig, progressStore, claimStore, language);
        convoyManager.initCitizensCheck();

        Bukkit.getPluginManager().registerEvents(new CitizensListeners(convoyManager, language, routesConfig), this);
        Bukkit.getPluginManager().registerEvents(new DepositListener(convoyManager), this);
        Bukkit.getPluginManager().registerEvents(new RoutesGuiListener(convoyManager, language), this);

        if (getCommand("convoy") != null) {
            getCommand("convoy").setExecutor(new ConvoyCommand(this, convoyManager, routesConfig, claimStore, language));
        } else {
            getLogger().warning("Command 'convoy' not found in plugin.yml – please add it!");
        }

        getLogger().info("BetterTradeConvoys enabled.");
    }

    @Override
    public void onDisable() {
        if (convoyManager != null) {
            convoyManager.failAllActiveOnShutdown();
        }

        if (progressStore != null) {
            progressStore.save();
        }
        if (claimStore != null) {
            try {
                ClaimStore.class.getMethod("save");
                claimStore.save();
            } catch (NoSuchMethodException ignored) {}
        }

        getLogger().info("BetterTradeConvoys disabled.");
    }

    private void saveDefaultIfMissing(String resource) {
        File target = new File(getDataFolder(), resource);
        if (!target.exists()) {
            saveResource(resource, false);
        }
    }

    public LanguageManager language() {
        return language;
    }

    public RoutesConfig routes() {
        return routesConfig;
    }

    public PlayerProgressStore progress() {
        return progressStore;
    }

    public ClaimStore claims() {
        return claimStore;
    }

    public ConvoyManager convoys() {
        return convoyManager;
    }
}
