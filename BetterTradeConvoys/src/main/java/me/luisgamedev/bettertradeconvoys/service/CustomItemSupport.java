package me.luisgamedev.bettertradeconvoys.service;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Utility for fetching custom items from supported item plugins.
 * Uses reflection so that no hard dependencies are required at compile time.
 */
public final class CustomItemSupport {

    private static final Map<String, Function<String, ItemStack>> PROVIDERS = new HashMap<>();

    static {
        registerOraxen();
        registerItemsAdder();
        registerNexo();
        registerDivinity();
        registerMMOCore();
    }

    private static void registerOraxen() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Oraxen")) return;
        try {
            Class<?> api = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            Method get = api.getMethod("getItemById", String.class);
            Method build = Class.forName("io.th0rgal.oraxen.items.OraxenItem").getMethod("build");
            PROVIDERS.put("oraxen", id -> {
                try {
                    Object item = get.invoke(null, id);
                    if (item == null) return null;
                    return (ItemStack) build.invoke(item);
                } catch (Exception e) {
                    return null;
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void registerItemsAdder() {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return;
        try {
            Class<?> api = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Method get = api.getMethod("getInstance", String.class);
            Method stack = api.getMethod("getItemStack");
            PROVIDERS.put("itemsadder", id -> {
                try {
                    Object obj = get.invoke(null, id);
                    if (obj == null) return null;
                    return (ItemStack) stack.invoke(obj);
                } catch (Exception e) {
                    return null;
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void registerNexo() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Nexo")) return;
        try {
            Class<?> api = Class.forName("com.nexomc.nexo.api.NexoItems");
            Method get = api.getMethod("getItem", String.class);
            PROVIDERS.put("nexo", id -> {
                try {
                    Object result = get.invoke(null, id);
                    if (result instanceof ItemStack is) return is;
                    if (result != null) {
                        Method build = result.getClass().getMethod("build");
                        return (ItemStack) build.invoke(result);
                    }
                    return null;
                } catch (Exception e) {
                    return null;
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void registerDivinity() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Divinity")) return;
        try {
            Class<?> api = Class.forName("me.swanis.divinity.api.DivinityAPI");
            Method get = api.getMethod("getItem", String.class);
            PROVIDERS.put("divinity", id -> {
                try {
                    Object result = get.invoke(null, id);
                    if (result instanceof ItemStack is) return is;
                    if (result != null) {
                        Method toStack = result.getClass().getMethod("toItemStack");
                        return (ItemStack) toStack.invoke(result);
                    }
                    return null;
                } catch (Exception e) {
                    return null;
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void registerMMOCore() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MMOCore")) return;
        try {
            Class<?> api = Class.forName("net.Indyuce.mmocore.api.item.ItemManager");
            Object manager = api.getMethod("get").invoke(null);
            Method getItem = api.getMethod("getItem", String.class);
            Method toStack = Class.forName("io.lumine.mythic.lib.api.item.NBTItem").getMethod("toItem");
            PROVIDERS.put("mmocore", id -> {
                try {
                    Object item = getItem.invoke(manager, id);
                    if (item == null) return null;
                    return (ItemStack) toStack.invoke(item);
                } catch (Exception e) {
                    return null;
                }
            });
        } catch (Exception ignored) {
        }
    }

    private CustomItemSupport() {
    }

    public static ItemStack getItem(String plugin, String id) {
        Function<String, ItemStack> func = PROVIDERS.get(plugin.toLowerCase(Locale.ROOT));
        return func != null ? func.apply(id) : null;
    }
}

