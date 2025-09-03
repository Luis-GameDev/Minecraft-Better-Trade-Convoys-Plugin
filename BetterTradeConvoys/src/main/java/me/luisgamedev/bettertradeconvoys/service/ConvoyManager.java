package me.luisgamedev.bettertradeconvoys.service;

import me.luisgamedev.bettertradeconvoys.BetterTradeConvoys;
import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.model.*;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
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
        if (rd == null) return lang.format("errors.unknown_route", lang.p("route", routeId));

        // NPC ↔ Route erlaubt?
        if (!rd.npcIds().contains(npc.getId())) {
            return lang.format("errors.route_locked_permission", lang.p("route", routeId));
        }

        // Busy?
        if (activeByNpcId.containsKey(npc.getId())) {
            return lang.get("errors.npc_busy");
        }

        // Limits / Cooldown
        int usedToday = progress.getStartsToday(owner.getUniqueId(), routeId);
        if (rd.dailyLimit() > 0 && usedToday >= rd.dailyLimit()) {
            return lang.format("errors.daily_limit_reached", lang.p("route", routeId, "limit", rd.dailyLimit()));
        }
        int usedWeek = progress.getStartsThisWeek(owner.getUniqueId(), routeId);
        if (rd.weeklyLimit() > 0 && usedWeek >= rd.weeklyLimit()) {
            return lang.format("errors.weekly_limit_reached", lang.p("route", routeId, "limit", rd.weeklyLimit()));
        }
        int usedMonth = progress.getStartsThisMonth(owner.getUniqueId(), routeId);
        if (rd.monthlyLimit() > 0 && usedMonth >= rd.monthlyLimit()) {
            return lang.format("errors.monthly_limit_reached", lang.p("route", routeId, "limit", rd.monthlyLimit()));
        }

        long last = progress.getLastStartMillis(owner.getUniqueId(), routeId);
        long now = System.currentTimeMillis();
        if (rd.cooldownSeconds() > 0 && (now - last) / 1000 < rd.cooldownSeconds()) {
            long remain = rd.cooldownSeconds() - ((now - last) / 1000);
            return lang.format("errors.cooldown_active", lang.p("route", routeId, "seconds", remain));
        }

        // Start/Home aus erstem WaypointStep
        if (rd.steps() == null || rd.steps().isEmpty()) {
            return lang.format("errors.unknown_route", lang.p("route", routeId));
        }
        Location start = null;
        int firstWpIdx = -1;
        for (int i = 0; i < rd.steps().size(); i++) {
            RouteStep s = rd.steps().get(i);
            if (s instanceof WaypointStep w) {
                start = w.getLoc();
                firstWpIdx = i;
                break;
            }
        }
        if (start == null || start.getWorld() == null) {
            return lang.format("errors.unknown_route", lang.p("route", routeId));
        }

        // NPC an Start setzen
        try {
            if (npc.isSpawned()) npc.despawn();
            npc.spawn(start);
            npc.getNavigator().getDefaultParameters().speedModifier((float) rd.speed());
        } catch (Throwable ignored) {}

        UUID instanceId = UUID.randomUUID();

        List<ItemStack> carried = new ArrayList<>(); // via Drops gefüllt

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
        homeByInstance.put(instanceId, start);

        int nextIdx = findNextIndex(rd, firstWpIdx);
        if (nextIdx == -1) nextIdx = firstWpIdx;
        stepIndexByInstance.put(instanceId, nextIdx);
        expiresAtByInstance.put(instanceId, now + rd.expireSeconds() * 1000L);
        pausedByDistance.put(instanceId, false);
        waitingClaim.put(instanceId, false);

        // Require deposit
        requiredInputByInstance.put(instanceId, trade.input().clone());
        depositProgressByInstance.put(instanceId, 0);

        // Announce
        if (rd.announceStart()) {
            Bukkit.broadcastMessage(lang.formatRaw("info.announced_start",
                    lang.p("player", owner.getName(), "name", rd.displayName())));
        }

        // Spieler auffordern, zu droppen
        owner.sendMessage(lang.format("deposit.prompt",
                lang.p("amount", trade.input().getAmount(), "material", trade.input().getType().name())));

        // Ticker für Radius/Expire
        startTicker(inst, npc, rd);

        // Fortschritt/Dates jetzt erhöhen (Start initiiert)
        progress.incStartsToday(owner.getUniqueId(), routeId);
        progress.incStartsThisWeek(owner.getUniqueId(), routeId);
        progress.incStartsThisMonth(owner.getUniqueId(), routeId);
        progress.setLastStartMillis(owner.getUniqueId(), routeId, now);

        return lang.format("info.started", lang.p("name", rd.displayName()));
    }

    private int findNextIndex(RouteDefinition rd, int from) {
        for (int i = from + 1; i < rd.steps().size(); i++) {
            if (rd.steps().get(i) instanceof WaypointStep) return i;
        }
        return -1;
    }

    private void navigateToCurrentStep(NPC npc, ConvoyInstance inst, RouteDefinition rd) {
        Integer idx = stepIndexByInstance.get(inst.getInstanceId());
        if (idx == null) return;
        if (idx < 0 || idx >= rd.steps().size()) return;

        RouteStep step = rd.steps().get(idx);
        if (step instanceof WaypointStep w) {
            npc.getNavigator().setTarget(w.getLoc());
        } else if (step instanceof TradeStep) {
            handleTradePauseThenNext(npc, inst, rd);
        }
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
        if (carried.isEmpty()) { inst.setPhase(ConvoyPhase.EXCHANGED); return; }

        // Wir matchen nur auf den *ersten* Input-Trade (simple Case)
        ItemStack total = mergeStacks(carried);
        for (TradeDefinition t : rd.trades()) {
            if (t.input().getType() == total.getType() && t.input().getAmount() == total.getAmount()) {
                inst.setCarried(Collections.singletonList(t.output().clone()));
                inst.setPhase(ConvoyPhase.EXCHANGED);
                return;
            }
        }
        inst.setPhase(ConvoyPhase.EXCHANGED);
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

        giveOrDrop(p, inst.getCarried());
        p.sendMessage(lang.get("info.claimed"));

        teleportNpcHome(npc, inst);
        removeInstanceOnly(npc, inst);
    }

    private void onReturnedHome(NPC npc, ConvoyInstance inst, RouteDefinition rd) {
        Player owner = Bukkit.getPlayer(inst.getOwner());
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

    public void onNpcDeath(NPC npc) {
        ConvoyInstance inst = activeByNpcId.get(npc.getId());
        if (inst == null) {
            respawnNpcHome(npc, npc.getStoredLocation());
            return;
        }

        var loc = npc.getStoredLocation();
        for (ItemStack it : inst.getCarried()) {
            if (it == null) continue;
            loc.getWorld().dropItemNaturally(loc, it.clone());
        }

        inst.setPhase(ConvoyPhase.FAILED);
        removeInstanceOnly(npc, inst);

        Player owner = Bukkit.getPlayer(inst.getOwner());
        if (owner != null) owner.sendMessage(lang.get("info.died_lost"));

        Location home = homeByInstance.get(inst.getInstanceId());
        if (home == null) home = npc.getStoredLocation();
        respawnNpcHome(npc, home);
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

    private void respawnNpcHome(NPC npc, Location home) {
        try {
            if (home != null && home.getWorld() != null) {
                if (npc.isSpawned()) npc.despawn();
                npc.spawn(home);
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
        if (carried.isEmpty()) return Collections.emptyList();
        var first = carried.get(0);
        for (TradeDefinition t : rd.trades()) {
            if (t.output().getType() == first.getType() && t.output().getAmount() == first.getAmount()) {
                return Collections.singletonList(t.input().clone());
            }
        }
        return new ArrayList<>(carried);
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

    // Wird vom DepositListener aufgerufen
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
            owner.sendMessage(lang.get("deposit.done"));

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

    // GUI-Einstieg – filtert Routen anhand npc-id und Permission
    public void openRoutesGui(Player p, NPC npc) {
        List<RouteDefinition> list = new ArrayList<>();
        for (var e : routes.getAll().values()) {
            RouteDefinition r = e;
            if (!r.npcIds().contains(npc.getId())) continue;

            boolean hasGlobal = p.hasPermission("bettertradeconvoys.routes"); // default: true
            boolean blocked = p.hasPermission("bettertradeconvoys.routes.block." + r.id());
            boolean explicitlyAllowed = p.hasPermission("bettertradeconvoys.routes." + r.id());
            boolean allowedPerm = (hasGlobal || explicitlyAllowed) && !blocked;
            if (!allowedPerm) continue;

            list.add(r);
        }
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
            ItemStack item;
            if (!r.trades().isEmpty()) {
                item = r.trades().get(0).input().clone();
            } else {
                item = new ItemStack(Material.PAPER);
            }
            var meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e" + r.displayName());
                List<String> lore = new ArrayList<>();
                lore.add("§7" + r.description());
                if (!r.trades().isEmpty()) {
                    ItemStack need = r.trades().get(0).input();
                    lore.add("§7Needs: " + need.getAmount() + "x " + need.getType().name());
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
