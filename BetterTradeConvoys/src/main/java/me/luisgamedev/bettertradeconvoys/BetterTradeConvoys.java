package me.luisgamedev.bettertradeconvoys;

import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.listeners.CitizensListeners;
import me.luisgamedev.bettertradeconvoys.listeners.DepositListener;
import me.luisgamedev.bettertradeconvoys.listeners.RoutesGuiListener;
import me.luisgamedev.bettertradeconvoys.service.ConvoyManager;
import me.luisgamedev.bettertradeconvoys.service.PlayerProgressStore;
import me.luisgamedev.bettertradeconvoys.service.RoutesConfig;
import me.luisgamedev.bettertradeconvoys.service.GuiConfig;
import me.luisgamedev.bettertradeconvoys.placeholders.BetterTradeConvoysExpansion;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;

public final class BetterTradeConvoys extends JavaPlugin {

    private LanguageManager language;
    private RoutesConfig routesConfig;
    private PlayerProgressStore progressStore;
    private GuiConfig guiConfig;
    private ConvoyManager convoyManager;
    private Economy economy;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        saveDefaultIfMissing("routes.yml");
        saveDefaultIfMissing("language.yml");
        saveDefaultIfMissing("gui.yml");

        language = new LanguageManager(this); language.load();
        routesConfig = new RoutesConfig(this); routesConfig.load();
        progressStore = new PlayerProgressStore(this); progressStore.load();
        guiConfig = new GuiConfig(this); guiConfig.load();

        setupEconomy();

        convoyManager = new ConvoyManager(this, routesConfig, progressStore, guiConfig, language);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new BetterTradeConvoysExpansion(this).register();
            getLogger().info("Hooked into PlaceholderAPI.");
        }
        convoyManager.initCitizensCheck();

        Bukkit.getPluginManager().registerEvents(new CitizensListeners(convoyManager, language, routesConfig), this);
        Bukkit.getPluginManager().registerEvents(new DepositListener(convoyManager), this);
        Bukkit.getPluginManager().registerEvents(new RoutesGuiListener(convoyManager, language), this);


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

    public ConvoyManager convoys() {
        return convoyManager;
    }

    public Economy economy() {
        return economy;
    }

    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found. Money trades disabled.");
            return;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().warning("No economy provider found. Money trades disabled.");
            return;
        }
        economy = rsp.getProvider();
        getLogger().info("Hooked into economy: " + economy.getName());
    }
}
