package me.luisgamedev.bettertradeconvoys.model;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public class ConvoyInstance {
    private final UUID instanceId;
    private final UUID owner;
    private final String routeId;
    private final int npcId;

    private ConvoyPhase phase;
    private int waypointIndex;
    private List<ItemStack> carried;
    private double carriedMoney;
    private double investedMoney;

    private long startedAt;

    public ConvoyInstance(UUID instanceId, UUID owner, String routeId, int npcId, ConvoyPhase phase, int waypointIndex, List<ItemStack> carried, long startedAt) {
        this.instanceId = instanceId;
        this.owner = owner;
        this.routeId = routeId;
        this.npcId = npcId;
        this.phase = phase;
        this.waypointIndex = waypointIndex;
        this.carried = carried;
        this.startedAt = startedAt;
        this.carriedMoney = 0.0;
        this.investedMoney = 0.0;
    }

    public UUID getInstanceId() { return instanceId; }
    public UUID getOwner() { return owner; }
    public String getRouteId() { return routeId; }
    public int getNpcId() { return npcId; }

    public ConvoyPhase getPhase() { return phase; }
    public void setPhase(ConvoyPhase phase) { this.phase = phase; }

    public int getWaypointIndex() { return waypointIndex; }
    public void setWaypointIndex(int waypointIndex) { this.waypointIndex = waypointIndex; }

    public List<ItemStack> getCarried() { return carried; }
    public void setCarried(List<ItemStack> carried) { this.carried = carried; }

    public double getCarriedMoney() { return carriedMoney; }
    public void setCarriedMoney(double carriedMoney) { this.carriedMoney = carriedMoney; }

    public double getInvestedMoney() { return investedMoney; }
    public void setInvestedMoney(double investedMoney) { this.investedMoney = investedMoney; }

    public long getStartedAt() { return startedAt; }
}
