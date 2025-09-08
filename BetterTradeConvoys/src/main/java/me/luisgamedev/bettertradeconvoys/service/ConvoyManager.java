package me.luisgamedev.bettertradeconvoys.service;

import me.luisgamedev.bettertradeconvoys.BetterTradeConvoys;
import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.model.*;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.CurrentLocation;
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

    private final Map<Integer, ConvoyInstance> activeByNpcId = new HashMap<>();
    private final Map<UUID, ConvoyInstance> activeByInstance = new HashMap<>();

    private final Map<UUID, Location> homeByInstance = new HashMap<>();
    private final Map<UUID, Integer> stepIndexByInstance = new HashMap<>();
    private final Map<UUID, Long> expiresAtByInstance = new HashMap<>();
    private final Map<UUID, Boolean> pausedByDistance = new HashMap<>();
    private final Map<UUID, Boolean> waitingClaim = new HashMap<>();
    private final Map<UUID, BukkitRunnable> tickers = new HashMap<>();

    // Deposit-Flow
    private final Map<UUID, ItemStack> requiredInputByInstance = new HashMap<>();
    private final Map<UUID, Integer> depositProgressByInstance = new HashMap<>();

    // GUI handling
    public static final String ROUTES_GUI_TITLE = "Routes";
    public static final int GUI_PREV_SLOT = 37;
    public static final int GUI_NEXT_SLOT = 43;
    private static final List<Integer> ROUTE_SLOTS;

    static {
        List<Integer> tmp = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                int slot = row * 9 + col;
                if (slot == GUI_PREV_SLOT || slot == GUI_NEXT_SLOT) continue;
                tmp.add(slot);
            }
        }
        ROUTE_SLOTS = Collections.unmodifiableList(tmp);
    }

    public static List<Integer> getRouteSlots() { return ROUTE_SLOTS; }
    public static final int GUI_PAGE_SIZE = ROUTE_SLOTS.size();

    private final Map<UUID, RoutesGuiState> openRouteMenus = new HashMap<>();

    public ConvoyManager(BetterTradeConvoys plugin, RoutesConfig routes, PlayerProgressStore progress, LanguageManager lang) {
        this.plugin = plugin;
        this.routes = routes;
        this.progress = progress;
        this.lang = lang;
    }

    public void initCitizensCheck() {
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            Bukkit.getConsoleSender().sendMessage(lang.get("errors.citizens_missing"));
        }
    }

    public String startConvoy(Player owner, NPC npc, String routeId, TradeDefinition trade) {
        RouteDefinition rd = routes.getRoute(routeId);

        if (rd == null) return lang.format("errors.unknown_route", lang.p("route", rd.displayName()));

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
        if (rd.dailyLimit() > 0 && usedToday >= rd.dailyLimit()) {
            return lang.format("errors.daily_limit_reached", lang.p("route", rd.displayName(), "limit", rd.dailyLimit()));
        }
        int usedWeek = progress.getStartsThisWeek(owner.getUniqueId(), routeId);
        if (rd.weeklyLimit() > 0 && usedWeek >= rd.weeklyLimit()) {
            return lang.format("errors.weekly_limit_reached", lang.p("route", rd.displayName(), "limit", rd.weeklyLimit()));
        }
        int usedMonth = progress.getStartsThisMonth(owner.getUniqueId(), routeId);
        if (rd.monthlyLimit() > 0 && usedMonth >= rd.monthlyLimit()) {
            return lang.format("errors.monthly_limit_reached", lang.p("route", rd.displayName(), "limit", rd.monthlyLimit()));
        }

        long last = progress.getLastStartMillis(owner.getUniqueId(), routeId);
        long now = System.currentTimeMillis();
        if (rd.cooldownSeconds() > 0 && (now - last) / 1000 < rd.cooldownSeconds()) {
            long remain = rd.cooldownSeconds() - ((now - last) / 1000);
            return lang.format("errors.cooldown_active", lang.p("route", rd.displayName(), "seconds", remain));
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

        return lang.format("info.started", lang.p("name", rd.displayName()));
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
            handleTradePauseThenNext(npc, inst, rd);
            return;
        }
        // For waypoint steps the message is sent once the NPC actually
        // arrives at the location in onNavigationComplete.
    }

    private void handleTradePauseThenNext(NPC npc, ConvoyInstance inst, RouteDefinition rd) {
        new BukkitRunnable() {
            @Override public void run() {
                int cur = stepIndexByInstance.getOrDefault(inst.getInstanceId(), 0);
                int next = findNextIndex(rd, cur);
                if (next == -1) {
                    onArrivedAtFinal(npc, inst, rd);
                } else {
                    stepIndexByInstance.put(inst.getInstanceId(), next);
                    navigateToCurrentStep(npc, inst, rd);
                }
            }
        }.runTaskLater(plugin, Math.max(0, rd.tradeDelaySeconds()) * 20L);
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
                Location current = npc.getEntity().getLocation();
                if (current.distance(w.getLoc()) > 0.5) {
                    npc.getNavigator().setTarget(w.getLoc());
                    return;
                }
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
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (npc.isSpawned()) npc.despawn();
                        npc.spawn(target);
                    } catch (Exception ignored) { }
                });
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
                }
            }
        };
        r.runTaskTimer(plugin, 10L, 10L);
        tickers.put(inst.getInstanceId(), r);
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
        private int page;

        public RoutesGuiState(NPC npc, List<RouteDefinition> routes) {
            this.npc = npc;
            this.routes = routes;
            this.page = 0;
        }

        public NPC getNpc() { return npc; }
        public List<RouteDefinition> getRoutes() { return routes; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
    }

    public void openRoutesGui(Player p, NPC npc) {
        if (!p.hasPermission("bettertradeconvoys.use")) {
            p.sendMessage(lang.get("errors.no_permission"));
            return;
        }

        List<RouteDefinition> list = new ArrayList<>(routes.getAll().values());
        if (list.isEmpty()) {
            p.sendMessage(lang.get("gui.no_routes_here"));
            return;
        }

        RoutesGuiState state = new RoutesGuiState(npc, list);
        openRouteMenus.put(p.getUniqueId(), state);
        renderRoutesGui(p, state);
    }

    public void renderRoutesGui(Player p, RoutesGuiState state) {
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 54, ROUTES_GUI_TITLE);

        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        var gm = glass.getItemMeta();
        if (gm != null) { gm.setDisplayName(" "); glass.setItemMeta(gm); }

        for (int i = 0; i < inv.getSize(); i++) {
            int row = i / 9;
            int col = i % 9;
            if (row == 0 || row == 5 || col == 0 || col == 8) {
                inv.setItem(i, glass);
            }
        }

        int start = state.getPage() * GUI_PAGE_SIZE;
        int end = Math.min(start + GUI_PAGE_SIZE, state.getRoutes().size());
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
                    item = new ItemStack(Material.PAPER);
                }
            } else {
                item = new ItemStack(Material.PAPER);
            }
            var meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e" + r.displayName());
                List<String> lore = new ArrayList<>();
                lore.add("§7" + r.description());
                if (t != null) {
                    if (t.inputItem() != null) {
                        ItemStack need = t.inputItem();
                        lore.add("§7Needs: " + need.getAmount() + "x " + need.getType().name());
                    } else if (t.inputMoney() > 0) {
                        lore.add("§7Needs: $" + t.inputMoney());
                    }
                }
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            int slot = ROUTE_SLOTS.get(idx - start);
            inv.setItem(slot, item);
        }

        if (state.getPage() > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            var pm = prev.getItemMeta();
            if (pm != null) { pm.setDisplayName("§ePrev"); prev.setItemMeta(pm); }
            inv.setItem(GUI_PREV_SLOT, prev);
        }
        if ((state.getPage() + 1) * GUI_PAGE_SIZE < state.getRoutes().size()) {
            ItemStack next = new ItemStack(Material.ARROW);
            var nm = next.getItemMeta();
            if (nm != null) { nm.setDisplayName("§eNext"); next.setItemMeta(nm); }
            inv.setItem(GUI_NEXT_SLOT, next);
        }

        p.openInventory(inv);
    }
}
