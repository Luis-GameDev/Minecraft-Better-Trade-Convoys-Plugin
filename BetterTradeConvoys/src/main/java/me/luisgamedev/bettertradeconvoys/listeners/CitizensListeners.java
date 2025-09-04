package me.luisgamedev.bettertradeconvoys.listeners;

import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.service.ConvoyManager;
import me.luisgamedev.bettertradeconvoys.service.RoutesConfig;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class CitizensListeners implements Listener {

    private final ConvoyManager manager;
    private final LanguageManager lang;
    private final RoutesConfig routes;

    public CitizensListeners(ConvoyManager manager, LanguageManager lang, RoutesConfig routes) {
        this.manager = manager;
        this.lang = lang;
        this.routes = routes;
    }

    @EventHandler
    public void onNavComplete(NavigationCompleteEvent event) {
        if (event.getNPC() == null) return;
        manager.onNavigationComplete(event.getNPC());
    }

    @EventHandler
    public void onNpcDeath(NPCDeathEvent event) {
        if (event.getNPC() == null) return;
        manager.onNpcDeath(event.getNPC());
    }

    @EventHandler
    public void onRightClick(NPCRightClickEvent event) {
        var npc = event.getNPC();
        var inst = manager.getByNpcId(npc.getId());
        if (inst != null) {
            Player p = event.getClicker();
            if (!p.getUniqueId().equals(inst.getOwner())) return;
            manager.handleOwnerRightClickAtNpc(p, npc, inst);
            return;
        }

        if (routes.getAll().isEmpty()) {
            return;
        }

        manager.openRoutesGui(event.getClicker(), npc);
    }
}
