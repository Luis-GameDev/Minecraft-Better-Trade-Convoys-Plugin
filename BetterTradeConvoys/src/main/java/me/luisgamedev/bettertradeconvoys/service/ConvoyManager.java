package me.luisgamedev.bettertradeconvoys.service;

import me.luisgamedev.bettertradeconvoys.BetterTradeConvoys;
import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.model.*;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.CurrentLocation;
import net.citizensnpcs.api.event.DespawnReason;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class ConvoyManager {

    public static final String META_INSTANCE = "btc-instance-id";

    private final BetterTradeConvoys plugin;
    private final RoutesConfig routes;
    private final PlayerProgressStore progress;
    private final LanguageManager lang;
    private final GuiConfig guiConfig;

    private final Map<Integer, ConvoyInstance> activeByNpcId = new HashMap<>();
    private final Map<UUID, ConvoyInstance> activeByInstance = new HashMap<>();

    private final Map<UUID, Location> homeByInstance = new HashMap<>();
    private final Map<UUID, Integer> stepIndexByInstance = new HashMap<>();
    private final Map<UUID, Long> expiresAtByInstance = new HashMap<>();
    private final Map<UUID, Boolean> pausedByDistance = new HashMap<>();
    private final Map<UUID, Boolean> waitingClaim = new HashMap<>();
    private final Map<UUID, BukkitRunnable> tickers = new HashMap<>();
    private final Map<UUID, Integer> movementRetryByInstance = new HashMap<>();
    private final Map<UUID, Integer> stuckTicksByInstance = new HashMap<>();
    private final Map<UUID, Location> lastPositionByInstance = new HashMap<>();
    private final Map<UUID, Location> lastReachedWaypointByInstance = new HashMap<>();

    // Deposit-Flow
    private final Map<UUID, ItemStack> requiredInputByInstance = new HashMap<>();
    private final Map<UUID, Integer> depositProgressByInstance = new HashMap<>();

    // GUI handling

    private final Map<UUID, RoutesGuiState> openRouteMenus = new HashMap<>();

    public ConvoyManager(BetterTradeConvoys plugin, RoutesConfig routes, PlayerProgressStore progress, GuiConfig guiConfig, LanguageManager lang) {
        this.plugin = plugin;
        this.routes = routes;
        this.progress = progress;
        this.lang = lang;
        this.guiConfig = guiConfig;
    }

    public void initCitizensCheck() {
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            Bukkit.getConsoleSender().sendMessage(lang.get("errors.citizens_missing"));
        }
    }

    public String startConvoy(Player owner, NPC npc, String routeId, TradeDefinition trade) {
        RouteDefinition rd = routes.getRoute(routeId);

        // Route might not exist or might not be offered by this NPC.
        if (rd == null) {
            return lang.format("errors.unknown_route", lang.p("route", routeId));
        }
        if (!rd.npcIds().contains(npc.getId())) {
            return lang.format("errors.unknown_route", lang.p("route", rd.displayName()));
        }

        if ((trade.inputMoney() > 0 || trade.outputMoney() > 0) && plugin.economy() == null) {
            return lang.get("errors.vault_missing");
        }
        if (trade.inputMoney() > 0 && plugin.economy() != null) {
            if (!plugin.economy().has(owner, trade.inputMoney())) {
                return lang.format("errors.not_enough_money", lang.p("amount", trade.inputMoney()));
            }
        }

        if (activeByNpcId.containsKey(npc.getId())) {
            return lang.get("errors.npc_busy");
        }

        int usedToday = progress.getStartsToday(owner.getUniqueId(), routeId);
        if (rd.dailyLimit() >= 0 && usedToday >= rd.dailyLimit()) {
            return lang.format("errors.daily_limit_reached", lang.p("route", rd.displayName(), "limit", rd.dailyLimit()));
        }
        int usedWeek = progress.getStartsThisWeek(owner.getUniqueId(), routeId);
        if (rd.weeklyLimit() >= 0 && usedWeek >= rd.weeklyLimit()) {
            return lang.format("errors.weekly_limit_reached", lang.p("route", rd.displayName(), "limit", rd.weeklyLimit()));
        }
        int usedMonth = progress.getStartsThisMonth(owner.getUniqueId(), routeId);
        if (rd.monthlyLimit() >= 0 && usedMonth >= rd.monthlyLimit()) {
            return lang.format("errors.monthly_limit_reached", lang.p("route", rd.displayName(), "limit", rd.monthlyLimit()));
        }

        long last = progress.getLastStartMillis(owner.getUniqueId(), routeId);
        long now = System.currentTimeMillis();
        if (rd.cooldownSeconds() > 0 && (now - last) / 1000 < rd.cooldownSeconds()) {
            long remain = rd.cooldownSeconds() - ((now - last) / 1000);
            return lang.format(owner, "errors.cooldown_active", lang.p("route", rd.displayName(), "seconds", remain));
        }

        if (rd.steps() == null || rd.steps().isEmpty()) {
            return lang.format("errors.unknown_route", lang.p("route", rd.displayName()));
        }
        Location start = null;
        int firstWpIdx = -1;
        for (int i = 0; i < rd.steps().size(); i++) {
            RouteStep s = rd.steps().get(i);
            if (s instanceof WaypointStep w) {
                // clone to avoid Citizens mutating the original Location instance
                start = w.getLoc().clone();
                firstWpIdx = i;
                break;
            }
        }
        if (start == null || start.getWorld() == null) {
            return lang.format("errors.unknown_route", lang.p("route", rd.displayName()));
        }

        // remember the location the NPC originally stood at so it can be
        // restored if the convoy fails or the NPC dies during the run
        Location originalHome = null;
        try {
            originalHome = npc.getStoredLocation();
        } catch (Throwable ignored) {}

        try {
            if (npc.isSpawned()) npc.despawn();
            // spawn with a clone so the stored home Location remains unchanged
            npc.spawn(start.clone());
            npc.getNavigator().getDefaultParameters().speedModifier((float) rd.speed());
        } catch (Throwable ignored) {}

        UUID instanceId = UUID.randomUUID();

        List<ItemStack> carried = new ArrayList<>();

        ConvoyInstance inst = new ConvoyInstance(
                instanceId,
                owner.getUniqueId(),
                rd.id(),
                npc.getId(),
                ConvoyPhase.GOING_TO_DEST,
                0,
                carried,
                now
        );

        npc.data().setPersistent(META_INSTANCE, instanceId.toString());
        activeByNpcId.put(npc.getId(), inst);
        activeByInstance.put(instanceId, inst);
        // store a clone of the NPC's original position so it can be restored on death
        if (originalHome != null) {
            homeByInstance.put(instanceId, originalHome.clone());
        } else {
            homeByInstance.put(instanceId, start.clone());
        }

        int nextIdx = findNextIndex(rd, firstWpIdx);
        if (nextIdx == -1) nextIdx = firstWpIdx;
        stepIndexByInstance.put(instanceId, nextIdx);
        expiresAtByInstance.put(instanceId, now + rd.expireSeconds() * 1000L);
        pausedByDistance.put(instanceId, false);
        waitingClaim.put(instanceId, false);
        movementRetryByInstance.put(instanceId, 0);
        stuckTicksByInstance.put(instanceId, 0);
        lastPositionByInstance.put(instanceId, start.clone());
        lastReachedWaypointByInstance.put(instanceId, start.clone());

        if (trade.inputItem() != null) {
            requiredInputByInstance.put(instanceId, trade.inputItem().clone());
            depositProgressByInstance.put(instanceId, 0);
        } else {
            requiredInputByInstance.put(instanceId, null);
            depositProgressByInstance.put(instanceId, 0);
            if (trade.inputMoney() > 0 && plugin.economy() != null) {
                plugin.economy().withdrawPlayer(owner, trade.inputMoney());
                inst.setInvestedMoney(trade.inputMoney());
            }
        }

        if (rd.announceStart()) {
            Bukkit.broadcastMessage(lang.formatRaw("info.announced_start",
                    lang.p("name", rd.displayName())));
        }

        startTicker(inst, npc, rd);
        if (trade.inputItem() == null) {
            navigateToCurrentStep(npc, inst, rd);
        }

        progress.incStartsToday(owner.getUniqueId(), routeId);
        progress.incStartsThisWeek(owner.getUniqueId(), routeId);
        progress.incStartsThisMonth(owner.getUniqueId(), routeId);
        progress.setLastStartMillis(owner.getUniqueId(), routeId, now);

        return lang.format(owner, "info.started", lang.p("name", rd.displayName()));
    }

    private int findNextIndex(RouteDefinition rd, int from) {
        int next = from + 1;
        return next < rd.steps().size() ? next : -1;
    }

    private void navigateToCurrentStep(NPC npc, ConvoyInstance inst, RouteDefinition rd) {
        Integer idx = stepIndexByInstance.get(inst.getInstanceId());
        if (idx == null) return;
        if (idx < 0 || idx >= rd.steps().size()) return;

        RouteStep step = rd.steps().get(idx);
        if (step instanceof WaypointStep w) {
            npc.getNavigator().setTarget(w.getLoc());
        } else if (step instanceof TradeStep) {
            sendStepMessage(inst, step);
            handleTradePauseThenNext(npc, inst, rd, idx);
            return;
        }
        // For waypoint steps the message is sent once the NPC actually
        // arrives at the location in onNavigationComplete.
    }

    private void handleTradePauseThenNext(NPC npc, ConvoyInstance inst, RouteDefinition rd, int curIdx) {
        new BukkitRunnable() {
            @Override public void run() {
                int next = findNextIndex(rd, curIdx);
                if (next == -1) {
                    onArrivedAtFinal(npc, inst, rd);
                } else {
                    stepIndexByInstance.put(inst.getInstanceId(), next);
                    navigateToCurrentStep(npc, inst, rd);
                }
            }
        }.runTaskLater(plugin, Math.max(1, rd.tradeDelaySeconds() * 20L));
    }

    public void onNavigationComplete(NPC npc) {
        ConvoyInstance inst = activeByNpcId.get(npc.getId());
        if (inst == null) return;

        RouteDefinition rd = routes.getRoute(inst.getRouteId());
        if (rd == null) return;

        if (inst.getPhase() == ConvoyPhase.GOING_TO_DEST) {
            int cur = stepIndexByInstance.getOrDefault(inst.getInstanceId(), 0);
            RouteStep step = rd.steps().get(cur);

            if (step instanceof WaypointStep w) {
                Location current;
                try {
                    current = npc.getEntity().getLocation();
                } catch (Exception e) {
                    current = w.getLoc();
                }
                if (current.distance(w.getLoc()) > 1.5) {
                    npc.getNavigator().setTarget(w.getLoc());
                    return;
                }
            }

            if (step instanceof WaypointStep w) {
                lastReachedWaypointByInstance.put(inst.getInstanceId(), w.getLoc().clone());
            }

            sendStepMessage(inst, step);

            int next = findNextIndex(rd, cur);
            if (next == -1) {
                onArrivedAtFinal(npc, inst, rd);
                return;
            }
            stepIndexByInstance.put(inst.getInstanceId(), next);
            navigateToCurrentStep(npc, inst, rd);
        } else if (inst.getPhase() == ConvoyPhase.RETURNING) {
            onReturnedHome(npc, inst, rd);
        }
    }

    private void sendStepMessage(ConvoyInstance inst, RouteStep step) {
        String msg = step.getMessage();
        if (msg == null || msg.isEmpty()) return;
        Player owner = Bukkit.getPlayer(inst.getOwner());
        if (owner != null) {
            owner.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }

    private void onArrivedAtFinal(NPC npc, ConvoyInstance inst, RouteDefinition rd) {
        exchangeAtDestination(inst, rd);
        waitingClaim.put(inst.getInstanceId(), true);

        Player owner = Bukkit.getPlayer(inst.getOwner());
        if (owner != null) {
            owner.sendMessage(lang.format("info.completed_ready", lang.p("name", rd.displayName())));
            owner.sendMessage(lang.get("npc.claim_hint_at_goal"));
        }
    }

    private void exchangeAtDestination(ConvoyInstance inst, RouteDefinition rd) {
        if (inst.getPhase() != ConvoyPhase.GOING_TO_DEST) return;
        var carried = inst.getCarried();
        if (!carried.isEmpty()) {
            ItemStack total = mergeStacks(carried);
            for (TradeDefinition t : rd.trades()) {
                if (t.inputItem() != null && t.inputItem().getType() == total.getType() && t.inputItem().getAmount() == total.getAmount()) {
                    if (t.outputItem() != null) {
                        inst.setCarried(Collections.singletonList(t.outputItem().clone()));
                    } else if (t.outputMoney() > 0) {
                        inst.setCarried(Collections.emptyList());
                        inst.setCarriedMoney(t.outputMoney());
                    }
                    inst.setPhase(ConvoyPhase.EXCHANGED);
                    return;
                }
            }
            inst.setPhase(ConvoyPhase.EXCHANGED);
        } else {
            for (TradeDefinition t : rd.trades()) {
                if (t.inputMoney() > 0) {
                    if (t.outputItem() != null) {
                        inst.setCarried(Collections.singletonList(t.outputItem().clone()));
                    } else if (t.outputMoney() > 0) {
                        inst.setCarriedMoney(t.outputMoney());
                    }
                    inst.setPhase(ConvoyPhase.EXCHANGED);
                    return;
                }
            }
            inst.setPhase(ConvoyPhase.EXCHANGED);
        }
    }

    private ItemStack mergeStacks(List<ItemStack> list) {
        if (list.isEmpty()) return null;
        ItemStack first = list.get(0).clone();
        int sum = 0;
        for (ItemStack s : list) if (s != null && s.getType() == first.getType()) sum += s.getAmount();
        first.setAmount(sum);
        return first;
    }

    public void handleOwnerRightClickAtNpc(Player p, NPC npc, ConvoyInstance inst) {
        RouteDefinition rd = routes.getRoute(inst.getRouteId());
        if (rd == null) return;

        if (!p.getUniqueId().equals(inst.getOwner())) {
            p.sendMessage(lang.get("errors.not_owner"));
            return;
        }
        boolean ready = waitingClaim.getOrDefault(inst.getInstanceId(), false);
        if (!ready) {
            p.sendMessage(lang.get("errors.claim_not_ready"));
            return;
        }

        if (plugin.economy() != null && inst.getCarriedMoney() > 0) {
            plugin.economy().depositPlayer(p, inst.getCarriedMoney());
            inst.setCarriedMoney(0);
        }
        giveOrDrop(p, inst.getCarried());
        p.sendMessage(lang.get("info.claimed"));

        teleportNpcHome(npc, inst);
        removeInstanceOnly(npc, inst);
    }

    private void onReturnedHome(NPC npc, ConvoyInstance inst, RouteDefinition rd) {
        Player owner = Bukkit.getPlayer(inst.getOwner());
        if (plugin.economy() != null && inst.getCarriedMoney() > 0) {
            plugin.economy().depositPlayer(owner != null ? owner : Bukkit.getOfflinePlayer(inst.getOwner()), inst.getCarriedMoney());
            inst.setCarriedMoney(0);
        }
        if (owner != null) {
            giveOrDrop(owner, inst.getCarried());
            owner.sendMessage(lang.format("info.completed_ready", lang.p("name", rd.displayName())));
        } else {
            Location loc = npc.getStoredLocation();
            for (ItemStack it : inst.getCarried()) {
                if (it == null) continue;
                loc.getWorld().dropItemNaturally(loc, it.clone());
            }
        }
        teleportNpcHome(npc, inst);
        removeInstanceOnly(npc, inst);
    }

    public void onNpcDeath(NPC npc, Player killer) {
        ConvoyInstance inst = activeByNpcId.get(npc.getId());
        if (inst == null) {
            Location home = npc.getStoredLocation();
            if (home == null) {
                try {
                    var entity = npc.getEntity();
                    if (entity != null) home = entity.getLocation();
                } catch (Exception ignored) {}
            }
            resetNpcHomeLocation(npc, home);
            respawnNpc(home, npc);
            return;
        }

        var loc = npc.getStoredLocation();
        for (ItemStack it : inst.getCarried()) {
            if (it == null) continue;
            loc.getWorld().dropItemNaturally(loc, it.clone());
        }

        if (plugin.economy() != null && killer != null && inst.getInvestedMoney() > 0) {
            plugin.economy().depositPlayer(killer, inst.getInvestedMoney());
            inst.setInvestedMoney(0);
        }

        inst.setPhase(ConvoyPhase.FAILED);

        Location home = homeByInstance.get(inst.getInstanceId());
        removeInstanceOnly(npc, inst);

        Player owner = Bukkit.getPlayer(inst.getOwner());
        if (owner != null) owner.sendMessage(lang.get("info.died_lost"));

        if (home == null) home = npc.getStoredLocation();
        resetNpcHomeLocation(npc, home);
        respawnNpc(home, npc);
    }

    private void respawnNpc(Location home, NPC npc) {
        try {
            if (home != null && home.getWorld() != null) {
                Location target = home.clone();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                        var entity = npc.getEntity();
                        if (entity != null) entity.remove();
                        if (npc.isSpawned()) npc.despawn(DespawnReason.PLUGIN);
                        npc.spawn(target);
                    } catch (Exception ignored) { }
                }, 20L);
            }
        } catch (Exception ignored) { }
    }

    private void teleportNpcHome(NPC npc, ConvoyInstance inst) {
        try {
            Location home = homeByInstance.get(inst.getInstanceId());
            if (home == null) home = npc.getStoredLocation();
            if (home != null && home.getWorld() != null) {
                if (npc.isSpawned()) npc.despawn();
                npc.spawn(home);
            }
        } catch (Exception ignored) { }
    }

    private void resetNpcHomeLocation(NPC npc, Location home) {
        try {
            if (home != null && home.getWorld() != null) {
                npc.getOrAddTrait(CurrentLocation.class).setLocation(home);
            }
        } catch (Exception ignored) { }
    }

    private void removeInstanceOnly(NPC npc, ConvoyInstance inst) {
        UUID id = inst.getInstanceId();
        BukkitRunnable r = tickers.remove(id);
        if (r != null) r.cancel();

        activeByNpcId.remove(npc.getId());
        activeByInstance.remove(id);
        stepIndexByInstance.remove(id);
        expiresAtByInstance.remove(id);
        pausedByDistance.remove(id);
        waitingClaim.remove(id);
        movementRetryByInstance.remove(id);
        stuckTicksByInstance.remove(id);
        lastPositionByInstance.remove(id);
        lastReachedWaypointByInstance.remove(id);
        requiredInputByInstance.remove(id);
        depositProgressByInstance.remove(id);
        homeByInstance.remove(id);

        try { npc.data().remove(META_INSTANCE); } catch (Exception ignored) { }
    }

    public void failAllActiveOnShutdown() {
        for (ConvoyInstance inst : new ArrayList<>(activeByInstance.values())) {
            NPC npc = CitizensAPI.getNPCRegistry().getById(inst.getNpcId());
            if (npc != null) {
                try { if (npc.isSpawned()) npc.despawn(); } catch (Exception ignored) { }
            }
        }
        for (BukkitRunnable r : tickers.values()) r.cancel();
        tickers.clear();
        activeByNpcId.clear();
        activeByInstance.clear();
        homeByInstance.clear();
        stepIndexByInstance.clear();
        expiresAtByInstance.clear();
        pausedByDistance.clear();
        waitingClaim.clear();
        movementRetryByInstance.clear();
        stuckTicksByInstance.clear();
        lastPositionByInstance.clear();
        lastReachedWaypointByInstance.clear();
        requiredInputByInstance.clear();
        depositProgressByInstance.clear();
    }

    public ConvoyInstance getByNpcId(int id) { return activeByNpcId.get(id); }

    public ConvoyInstance getActiveByOwner(UUID uuid) {
        for (var inst : activeByInstance.values()) {
            if (inst.getOwner().equals(uuid)) return inst;
        }
        return null;
    }

    private void startTicker(ConvoyInstance inst, NPC npc, RouteDefinition rd) {
        BukkitRunnable r = new BukkitRunnable() {
            @Override public void run() {
                Player owner = Bukkit.getPlayer(inst.getOwner());
                if (owner == null || !owner.isOnline()) return;

                long now = System.currentTimeMillis();
                long expiresAt = expiresAtByInstance.getOrDefault(inst.getInstanceId(), Long.MAX_VALUE);
                if (now > expiresAt) {
                    Player o = Bukkit.getPlayer(inst.getOwner());
                    List<ItemStack> refund = refundInput(inst, rd);
                    if (o != null) {
                        giveOrDrop(o, refund);
                        o.sendMessage(lang.get("info.expired_refunded"));
                    } else {
                        Location loc = npc.getStoredLocation();
                        for (ItemStack it : refund) {
                            if (it == null) continue;
                            loc.getWorld().dropItemNaturally(loc, it.clone());
                        }
                    }
                    teleportNpcHome(npc, inst);
                    removeInstanceOnly(npc, inst);
                    cancel();
                    return;
                }

                if (inst.getPhase() == ConvoyPhase.GOING_TO_DEST || inst.getPhase() == ConvoyPhase.EXCHANGED) {
                    boolean paused = pausedByDistance.getOrDefault(inst.getInstanceId(), false);
                    double dist;
                    try { dist = npc.getEntity().getLocation().distance(owner.getLocation()); }
                    catch (Throwable t) { dist = 0.0; }
                    boolean tooFar = dist > rd.followRadius();

                    if (tooFar && !paused) {
                        pausedByDistance.put(inst.getInstanceId(), true);
                        try { npc.getNavigator().cancelNavigation(); } catch (Throwable ignored) {}
                        owner.sendMessage(lang.format("info.paused_distance", lang.p("radius", (int) rd.followRadius())));
                    } else if (!tooFar && paused) {
                        pausedByDistance.put(inst.getInstanceId(), false);
                        navigateToCurrentStep(npc, inst, rd);
                    }

                    if (!tooFar && !paused && !waitingClaim.getOrDefault(inst.getInstanceId(), false)) {
                        ensureConvoyMovement(npc, inst, rd);
                    }
                }
            }
        };
        r.runTaskTimer(plugin, 10L, 10L);
        tickers.put(inst.getInstanceId(), r);
    }


    private void ensureConvoyMovement(NPC npc, ConvoyInstance inst, RouteDefinition rd) {
        UUID id = inst.getInstanceId();
        Integer idx = stepIndexByInstance.get(id);
        if (idx == null || idx < 0 || idx >= rd.steps().size()) return;

        RouteStep step = rd.steps().get(idx);
        if (!(step instanceof WaypointStep wp)) {
            stuckTicksByInstance.put(id, 0);
            movementRetryByInstance.put(id, 0);
            return;
        }

        Location current;
        try {
            if (npc.getEntity() == null) return;
            current = npc.getEntity().getLocation();
        } catch (Throwable ignored) {
            return;
        }

        Location previous = lastPositionByInstance.get(id);
        lastPositionByInstance.put(id, current.clone());

        double moved = previous != null && previous.getWorld() != null && current.getWorld() != null && previous.getWorld().equals(current.getWorld())
                ? previous.distance(current)
                : 0.0;
        boolean atTarget = current.getWorld() != null && wp.getLoc().getWorld() != null && current.getWorld().equals(wp.getLoc().getWorld())
                && current.distance(wp.getLoc()) <= 1.6;
        boolean navigating;
        try {
            navigating = npc.getNavigator().isNavigating();
        } catch (Throwable ignored) {
            navigating = false;
        }

        if (atTarget) {
            stuckTicksByInstance.put(id, 0);
            movementRetryByInstance.put(id, 0);
            return;
        }

        if (moved > 0.08 && navigating) {
            stuckTicksByInstance.put(id, 0);
            movementRetryByInstance.put(id, 0);
            return;
        }

        int stuckTicks = stuckTicksByInstance.getOrDefault(id, 0) + 1;
        stuckTicksByInstance.put(id, stuckTicks);

        if (stuckTicks < 8) return;
        stuckTicksByInstance.put(id, 0);

        int retries = movementRetryByInstance.getOrDefault(id, 0) + 1;
        movementRetryByInstance.put(id, retries);

        if (retries <= 3) {
            npc.getNavigator().cancelNavigation();
            navigateToCurrentStep(npc, inst, rd);
            return;
        }

        movementRetryByInstance.put(id, 0);
        Location fallback = lastReachedWaypointByInstance.get(id);
        if (fallback == null) fallback = wp.getLoc();
        if (fallback != null && fallback.getWorld() != null) {
            try {
                npc.teleport(fallback.clone(), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            } catch (Throwable ignored) {
                try {
                    if (npc.isSpawned()) npc.despawn();
                    npc.spawn(fallback.clone());
                } catch (Throwable ignored2) { }
            }
        }
        navigateToCurrentStep(npc, inst, rd);
    }

    private List<ItemStack> refundInput(ConvoyInstance inst, RouteDefinition rd) {
        var carried = inst.getCarried();
        if (!carried.isEmpty()) {
            var first = carried.get(0);
            for (TradeDefinition t : rd.trades()) {
                if (t.outputItem() != null && t.outputItem().getType() == first.getType() && t.outputItem().getAmount() == first.getAmount()) {
                    if (t.inputItem() != null) {
                        return Collections.singletonList(t.inputItem().clone());
                    } else if (t.inputMoney() > 0 && plugin.economy() != null) {
                        plugin.economy().depositPlayer(Bukkit.getOfflinePlayer(inst.getOwner()), t.inputMoney());
                        return Collections.emptyList();
                    }
                }
            }
            return new ArrayList<>(carried);
        } else {
            if (inst.getInvestedMoney() > 0 && plugin.economy() != null) {
                plugin.economy().depositPlayer(Bukkit.getOfflinePlayer(inst.getOwner()), inst.getInvestedMoney());
            }
            return Collections.emptyList();
        }
    }

    private void giveOrDrop(Player p, List<ItemStack> items) {
        for (ItemStack it : items) {
            if (it == null) continue;
            var leftover = p.getInventory().addItem(it);
            if (!leftover.isEmpty()) {
                leftover.values().forEach(stack -> p.getWorld().dropItemNaturally(p.getLocation(), stack));
            }
        }
    }

    public void onOwnerDeposited(Player owner, NPC npc, ConvoyInstance inst, ItemStack drop) {
        ItemStack need = requiredInputByInstance.get(inst.getInstanceId());
        if (need == null) return;

        if (drop.getType() != need.getType()) {
            owner.sendMessage(lang.format("deposit.wrong_item", lang.p("amount", need.getAmount(), "material", need.getType().name())));
            return;
        }
        int got = depositProgressByInstance.getOrDefault(inst.getInstanceId(), 0);
        got += drop.getAmount();
        int needAmount = need.getAmount();

        inst.getCarried().add(drop.clone());

        if (got >= needAmount) {
            depositProgressByInstance.put(inst.getInstanceId(), needAmount);

            RouteDefinition rd = routes.getRoute(inst.getRouteId());
            navigateToCurrentStep(npc, inst, rd);
        } else {
            depositProgressByInstance.put(inst.getInstanceId(), got);
            owner.sendMessage(lang.format("deposit.progress", lang.p(
                    "got", got, "need", needAmount, "material", need.getType().name()
            )));
        }
    }

    public RoutesGuiState getRoutesGui(UUID id) { return openRouteMenus.get(id); }
    public void closeRoutesGui(UUID id) { openRouteMenus.remove(id); }

    public static class RoutesGuiState {
        private final NPC npc;
        private final List<RouteDefinition> routes;
        private final GuiConfig.GuiLayout layout;
        private int page;

        public RoutesGuiState(NPC npc, List<RouteDefinition> routes, GuiConfig.GuiLayout layout) {
            this.npc = npc;
            this.routes = routes;
            this.layout = layout;
            this.page = 0;
        }

        public NPC getNpc() { return npc; }
        public List<RouteDefinition> getRoutes() { return routes; }
        public GuiConfig.GuiLayout getLayout() { return layout; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
    }

    public void openRoutesGui(Player p, NPC npc) {
        if (!p.hasPermission("bettertradeconvoys.use")) {
            p.sendMessage(lang.get("errors.no_permission"));
            return;
        }

        List<RouteDefinition> list = new ArrayList<>();
        for (RouteDefinition rd : routes.getAll().values()) {
            if (rd.npcIds().contains(npc.getId())) {
                list.add(rd);
            }
        }
        if (list.isEmpty()) {
            return;
        }

        String layoutId = !list.isEmpty() ? list.get(0).guiLayout() : "default";
        GuiConfig.GuiLayout layout = guiConfig.getLayoutOrDefault(layoutId);
        if (layout == null) return;
        RoutesGuiState state = new RoutesGuiState(npc, list, layout);
        openRouteMenus.put(p.getUniqueId(), state);
        renderRoutesGui(p, state);
    }

    public void renderRoutesGui(Player p, RoutesGuiState state) {
        GuiConfig.GuiLayout layout = state.getLayout();
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, layout.size(), layout.title());

        ItemStack glass = layout.borderItem().clone();

        for (int i = 0; i < inv.getSize(); i++) {
            int row = i / 9;
            int col = i % 9;
            if (row == 0 || row == (inv.getSize() / 9) - 1 || col == 0 || col == 8) {
                inv.setItem(i, glass);
            }
        }

        int pageSize = layout.routeSlots().size();
        int start = state.getPage() * pageSize;
        int end = Math.min(start + pageSize, state.getRoutes().size());
        for (int idx = start; idx < end; idx++) {
            RouteDefinition r = state.getRoutes().get(idx);
            TradeDefinition t = !r.trades().isEmpty() ? r.trades().get(0) : null;
            ItemStack item;
            if (t != null) {
                if (t.inputItem() != null) {
                    item = t.inputItem().clone();
                } else if (t.outputItem() != null) {
                    item = t.outputItem().clone();
                } else {
                    item = layout.routeItem().clone();
                }
            } else {
                item = layout.routeItem().clone();
            }
            if (layout.routeItemUseTradeTexture() && t != null) {
                if (t.inputItem() != null) {
                    item.setType(t.inputItem().getType());
                } else if (t.outputItem() != null) {
                    item.setType(t.outputItem().getType());
                }
            }
            applyPlaceholders(item, buildRoutePlaceholders(r, t, state.getPage() + 1));
            int slot = layout.routeSlots().get(idx - start);
            inv.setItem(slot, item);
        }

        if (state.getPage() > 0) {
            ItemStack prev = layout.prevItem().clone();
            applyPlaceholders(prev, Map.of("{page}", String.valueOf(state.getPage() + 1)));
            inv.setItem(layout.prevSlot(), prev);
        }
        if ((state.getPage() + 1) * pageSize < state.getRoutes().size()) {
            ItemStack next = layout.nextItem().clone();
            applyPlaceholders(next, Map.of("{page}", String.valueOf(state.getPage() + 2)));
            inv.setItem(layout.nextSlot(), next);
        }

        p.openInventory(inv);
    }
    private Map<String, String> buildRoutePlaceholders(RouteDefinition r, TradeDefinition t, int page) {
        Map<String, String> ph = new HashMap<>();
        ph.put("{route_id}", r.id());
        ph.put("{route_name}", r.displayName());
        ph.put("{route_description}", r.description());
        ph.put("{page}", String.valueOf(page));

        ph.put("{input_item_material}", "NONE");
        ph.put("{input_item_amount}", "0");
        ph.put("{output_item_material}", "NONE");
        ph.put("{output_item_amount}", "0");
        ph.put("{input_money}", "0");
        ph.put("{output_money}", "0");

        if (t != null) {
            if (t.inputItem() != null) {
                ph.put("{input_item_material}", t.inputItem().getType().name());
                ph.put("{input_item_amount}", String.valueOf(t.inputItem().getAmount()));
            }
            if (t.outputItem() != null) {
                ph.put("{output_item_material}", t.outputItem().getType().name());
                ph.put("{output_item_amount}", String.valueOf(t.outputItem().getAmount()));
            }
            ph.put("{input_money}", String.valueOf(t.inputMoney()));
            ph.put("{output_money}", String.valueOf(t.outputMoney()));
        }
        return ph;
    }

    private void applyPlaceholders(ItemStack item, Map<String, String> placeholders) {
        var meta = item.getItemMeta();
        if (meta == null) return;
        if (meta.getDisplayName() != null) {
            String name = meta.getDisplayName();
            for (var entry : placeholders.entrySet()) name = name.replace(entry.getKey(), entry.getValue());
            meta.setDisplayName(name);
        }
        if (meta.getLore() != null) {
            List<String> lore = new ArrayList<>();
            for (String line : meta.getLore()) {
                String out = line;
                for (var entry : placeholders.entrySet()) out = out.replace(entry.getKey(), entry.getValue());
                lore.add(out);
            }
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
    }

}
