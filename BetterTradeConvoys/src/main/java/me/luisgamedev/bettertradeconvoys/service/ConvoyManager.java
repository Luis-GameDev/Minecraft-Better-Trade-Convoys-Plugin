package me.luisgamedev.bettertradeconvoys.service;

import me.luisgamedev.bettertradeconvoys.BetterTradeConvoys;
import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.model.*;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Kompatibel mit:
 *  - ConvoyInstance: getWaypointIndex()/setWaypointIndex(), getPhase()/setPhase(), getCarried()/setCarried() ...
 *  - ConvoyPhase: GOING_TO_DEST, EXCHANGED, RETURNING, COMPLETE, FAILED
 *  - RouteDefinition mit steps (WaypointStep/TradeStep), speed, followRadius, expireSeconds, tradeDelaySeconds, announceStart
 */
public class ConvoyManager {

    public static final String META_INSTANCE = "btc-instance-id";

    private final BetterTradeConvoys plugin;
    private final RoutesConfig routes;
    private final PlayerProgressStore progress;
    private final ClaimStore claims;
    private final LanguageManager lang;

    private final Map<Integer, ConvoyInstance> activeByNpcId = new HashMap<>();
    private final Map<UUID, ConvoyInstance> activeByInstance = new HashMap<>();

    // Zusatz-States (damit ConvoyInstance unverändert bleiben kann)
    private final Map<UUID, Location> homeByInstance = new HashMap<>();
    private final Map<UUID, Integer> stepIndexByInstance = new HashMap<>();
    private final Map<UUID, Long> expiresAtByInstance = new HashMap<>();
    private final Map<UUID, Boolean> pausedByDistance = new HashMap<>();
    private final Map<UUID, Boolean> waitingClaim = new HashMap<>();
    private final Map<UUID, BukkitRunnable> tickers = new HashMap<>();

    public ConvoyManager(BetterTradeConvoys plugin, RoutesConfig routes, PlayerProgressStore progress, ClaimStore claims, LanguageManager lang) {
        this.plugin = plugin;
        this.routes = routes;
        this.progress = progress;
        this.claims = claims;
        this.lang = lang;
    }

    public void initCitizensCheck() {
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            Bukkit.getConsoleSender().sendMessage(lang.get("errors.citizens_missing"));
        }
    }

    public String startConvoy(Player owner, String routeId, TradeDefinition trade, ItemStack takenFromHand) {
        RouteDefinition rd = routes.getRoute(routeId);
        if (rd == null) return lang.format("errors.unknown_route", lang.p("route", routeId));

        // Limits / Cooldown
        int usedToday = progress.getStartsToday(owner.getUniqueId(), routeId);
        if (usedToday >= rd.dailyLimit()) {
            return lang.format("errors.daily_limit_reached", lang.p("route", routeId, "limit", rd.dailyLimit()));
        }
        long last = progress.getLastStartMillis(owner.getUniqueId(), routeId);
        long now = System.currentTimeMillis();
        if ((now - last) / 1000 < rd.cooldownSeconds()) {
            long remain = rd.cooldownSeconds() - ((now - last) / 1000);
            return lang.format("errors.cooldown_active", lang.p("route", routeId, "seconds", remain));
        }

        // Start/Home bestimmen = erster WaypointStep
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

        // NPC spawnen
        EntityType type = rd.npcType();
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(type, "Convoy");
        npc.setProtected(false);
        npc.spawn(start);
        try {
            npc.getNavigator().getDefaultParameters().speedModifier((float) rd.speed());
        } catch (Throwable ignored) { }

        UUID instanceId = UUID.randomUUID();

        // Carried = Input zu Beginn
        List<ItemStack> carried = new ArrayList<>();
        carried.add(takenFromHand.clone());

        ConvoyInstance inst = new ConvoyInstance(
                instanceId,
                owner.getUniqueId(),
                rd.id(),
                npc.getId(),
                ConvoyPhase.GOING_TO_DEST,
                0, // wir nutzen weiterhin waypointIndex-Feld, belegen aber separat stepIndexByInstance
                carried,
                now
        );

        npc.data().setPersistent(META_INSTANCE, instanceId.toString());
        activeByNpcId.put(npc.getId(), inst);
        activeByInstance.put(instanceId, inst);

        // Zusatz-States
        homeByInstance.put(instanceId, start);
        int nextIdx = findNextIndex(rd, firstWpIdx); // nächstes Ziel nach dem Start
        if (nextIdx == -1) nextIdx = firstWpIdx;     // falls nur ein Waypoint existiert
        stepIndexByInstance.put(instanceId, nextIdx);
        expiresAtByInstance.put(instanceId, now + rd.expireSeconds() * 1000L);
        pausedByDistance.put(instanceId, false);
        waitingClaim.put(instanceId, false);

        // Announce
        if (rd.announceStart()) {
            Bukkit.broadcastMessage(lang.formatRaw("info.announced_start",
                    lang.p("player", owner.getName(), "name", rd.displayName())));
        }

        // Loslaufen
        navigateToCurrentStep(npc, inst, rd);

        // Ticker
        startTicker(inst, npc, rd);

        // Fortschritt speichern
        progress.incStartsToday(owner.getUniqueId(), routeId);
        progress.setLastStartMillis(owner.getUniqueId(), routeId, now);

        return lang.format("info.started", lang.p("name", rd.displayName()));
    }

    /** Hilfsfunktion: finde den nächsten Step-Index nach 'from', der ein WaypointStep ist; -1 wenn keiner. */
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
            // Trade-Stopp = Wartezeit, danach weiter
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

    /** Citizens Navigation ist an einem Ziel angekommen. */
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
            // Rückkehr abgeschlossen -> auszahlen und aufräumen
            onReturnedHome(npc, inst, rd);
        }
    }

    private void onArrivedAtFinal(NPC npc, ConvoyInstance inst, RouteDefinition rd) {
        // Tausch am Ziel (Input -> Output)
        exchangeAtDestination(inst, rd);
        // am Ziel stehen bleiben, auf Claim warten
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
        if (carried.isEmpty()) return;

        var first = carried.get(0);
        for (TradeDefinition t : rd.trades()) {
            if (t.input().getType() == first.getType() && t.input().getAmount() == first.getAmount()) {
                inst.setCarried(Collections.singletonList(t.output().clone()));
                inst.setPhase(ConvoyPhase.EXCHANGED);
                return;
            }
        }
        // kein Match -> trotzdem EXCHANGED (= Ziel erreicht, aber nichts verändert)
        inst.setPhase(ConvoyPhase.EXCHANGED);
    }

    /** Owner rechtsklickt den NPC (Listener ruft diese Methode). */
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

        // Belohnung auszahlen
        claims.add(inst.getOwner(), inst.getCarried());
        p.sendMessage(lang.get("info.claimed"));

        // NPC nach Hause und aufräumen
        teleportNpcHome(npc, inst);
        despawnAndRemove(npc, inst);
    }

    private void onReturnedHome(NPC npc, ConvoyInstance inst, RouteDefinition rd) {
        claims.add(inst.getOwner(), inst.getCarried());
        Player owner = Bukkit.getPlayer(inst.getOwner());
        if (owner != null) {
            owner.sendMessage(lang.format("info.completed_ready", lang.p("name", rd.displayName())));
        }
        despawnAndRemove(npc, inst);
    }

    /** NPC gestorben -> Items droppen, Instanz beenden, NPC an Home neu spawnen (ohne Run). */
    public void onNpcDeath(NPC npc) {
        ConvoyInstance inst = activeByNpcId.get(npc.getId());
        if (inst == null) {
            // Kein aktiver Run -> respawn an gespeicherter Location
            respawnNpcHome(npc, npc.getStoredLocation());
            return;
        }

        // Droppe aktuell getragene Items
        var loc = npc.getStoredLocation();
        for (ItemStack it : inst.getCarried()) {
            if (it == null) continue;
            loc.getWorld().dropItemNaturally(loc, it.clone());
        }

        inst.setPhase(ConvoyPhase.FAILED);
        // Instanz entfernen, NPC NICHT deregistrieren, damit wir ihn respawnen können
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

    private void despawnAndRemove(NPC npc, ConvoyInstance inst) {
        // alle Ticker/States schließen
        UUID id = inst.getInstanceId();
        BukkitRunnable r = tickers.remove(id);
        if (r != null) r.cancel();

        activeByNpcId.remove(npc.getId());
        activeByInstance.remove(id);
        homeByInstance.remove(id);
        stepIndexByInstance.remove(id);
        expiresAtByInstance.remove(id);
        pausedByDistance.remove(id);
        waitingClaim.remove(id);

        try {
            if (npc.isSpawned()) npc.despawn();
            CitizensAPI.getNPCRegistry().deregister(npc);
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
        homeByInstance.remove(id);

        // NPC im Registry belassen, damit respawn funktioniert
        try {
            npc.data().remove(META_INSTANCE);
        } catch (Exception ignored) { }
    }

    public void failAllActiveOnShutdown() {
        // Nur sauber beenden (keine Random-Stayer)
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
    }

    public ConvoyInstance getByNpcId(int id) { return activeByNpcId.get(id); }

    private void startTicker(ConvoyInstance inst, NPC npc, RouteDefinition rd) {
        BukkitRunnable r = new BukkitRunnable() {
            @Override public void run() {
                Player owner = Bukkit.getPlayer(inst.getOwner());
                if (owner == null || !owner.isOnline()) return;

                long now = System.currentTimeMillis();
                long expiresAt = expiresAtByInstance.getOrDefault(inst.getInstanceId(), Long.MAX_VALUE);
                if (now > expiresAt) {
                    // Ablauf -> Input erstatten
                    claims.add(inst.getOwner(), refundInput(inst, rd));
                    Player o = Bukkit.getPlayer(inst.getOwner());
                    if (o != null) o.sendMessage(lang.get("info.expired_refunded"));
                    teleportNpcHome(npc, inst);
                    despawnAndRemove(npc, inst);
                    cancel();
                    return;
                }

                // Follow-Radius prüfen
                if (inst.getPhase() == ConvoyPhase.GOING_TO_DEST || inst.getPhase() == ConvoyPhase.EXCHANGED) {
                    boolean paused = pausedByDistance.getOrDefault(inst.getInstanceId(), false);
                    double dist;
                    try {
                        dist = npc.getEntity().getLocation().distance(owner.getLocation());
                    } catch (Throwable t) {
                        dist = 0.0;
                    }
                    boolean tooFar = dist > rd.followRadius();

                    if (tooFar && !paused) {
                        pausedByDistance.put(inst.getInstanceId(), true);
                        try { npc.getNavigator().cancelNavigation(); } catch (Throwable ignored) {}
                        owner.sendMessage(lang.format("info.paused_distance", lang.p("radius", (int) rd.followRadius())));
                    } else if (!tooFar && paused) {
                        pausedByDistance.put(inst.getInstanceId(), false);
                        navigateToCurrentStep(npc, inst, rd);
                        owner.sendMessage(lang.format("info.resumed_movement", lang.p("radius", (int) rd.followRadius())));
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
        // Wenn bereits Output getragen wird, versuche den passenden Input zu finden
        for (TradeDefinition t : rd.trades()) {
            if (t.output().getType() == first.getType() && t.output().getAmount() == first.getAmount()) {
                return Collections.singletonList(t.input().clone());
            }
        }
        // Fallback: gib zurück, was er gerade trägt
        return new ArrayList<>(carried);
    }
}
