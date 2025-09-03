package me.luisgamedev.bettertradeconvoys.listeners;

import me.luisgamedev.bettertradeconvoys.service.ConvoyManager;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;

public class DepositListener implements Listener {

    private final ConvoyManager manager;

    public DepositListener(ConvoyManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        Item itemEnt = event.getItemDrop();

        var inst = manager.getActiveByOwner(p.getUniqueId());
        if (inst == null) return;

        NPC npc = CitizensAPI.getNPCRegistry().getById(inst.getNpcId());
        if (npc == null || !npc.isSpawned()) return;

        double dist;
        try { dist = npc.getEntity().getLocation().distance(itemEnt.getLocation()); }
        catch (Throwable t) { return; }
        if (dist > 2.5) return;

        var stack = itemEnt.getItemStack();
        itemEnt.remove();

        manager.onOwnerDeposited(p, npc, inst, stack);
    }
}
