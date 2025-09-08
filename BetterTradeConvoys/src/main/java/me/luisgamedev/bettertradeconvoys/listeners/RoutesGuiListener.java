package me.luisgamedev.bettertradeconvoys.listeners;

import me.luisgamedev.bettertradeconvoys.language.LanguageManager;
import me.luisgamedev.bettertradeconvoys.model.RouteDefinition;
import me.luisgamedev.bettertradeconvoys.model.TradeDefinition;
import me.luisgamedev.bettertradeconvoys.service.ConvoyManager;
import me.luisgamedev.bettertradeconvoys.service.ConvoyManager.RoutesGuiState;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

public class RoutesGuiListener implements Listener {

    private final ConvoyManager manager;
    private final LanguageManager lang;

    public RoutesGuiListener(ConvoyManager manager, LanguageManager lang) {
        this.manager = manager;
        this.lang = lang;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        RoutesGuiState state = manager.getRoutesGui(p.getUniqueId());
        if (state == null) return;
        if (!event.getView().getTitle().equals(ConvoyManager.ROUTES_GUI_TITLE)) return;
        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot == ConvoyManager.GUI_PREV_SLOT) {
            if (state.getPage() > 0) {
                state.setPage(state.getPage() - 1);
                manager.renderRoutesGui(p, state);
            }
            return;
        }
        if (slot == ConvoyManager.GUI_NEXT_SLOT) {
            if ((state.getPage() + 1) * ConvoyManager.GUI_PAGE_SIZE < state.getRoutes().size()) {
                state.setPage(state.getPage() + 1);
                manager.renderRoutesGui(p, state);
            }
            return;
        }

        if (!ConvoyManager.getRouteSlots().contains(slot)) return;

        int indexInPage = ConvoyManager.getRouteSlots().indexOf(slot);
        int routeIndex = state.getPage() * ConvoyManager.GUI_PAGE_SIZE + indexInPage;
        if (routeIndex >= state.getRoutes().size()) return;

        RouteDefinition rd = state.getRoutes().get(routeIndex);
        if (rd.trades().isEmpty()) return;
        TradeDefinition trade = rd.trades().get(0);
        NPC npc = state.getNpc();
        if (trade.inputItem() != null) {
            ItemStack need = trade.inputItem().clone();
            if (!p.getInventory().containsAtLeast(need, need.getAmount())) {
                p.sendMessage(lang.format("deposit.wrong_item", lang.p("amount", need.getAmount(), "material", need.getType().name())));
                return;
            }

            p.getInventory().removeItem(need.clone());
            String result = manager.startConvoy(p, npc, rd.id(), trade);
            p.sendMessage(result);
            if (result.contains(ChatColor.RED.toString())) {
                p.getInventory().addItem(need);
                return;
            }

            var inst = manager.getActiveByOwner(p.getUniqueId());
            if (inst != null) {
                manager.onOwnerDeposited(p, npc, inst, need);
            }
        } else {
            String result = manager.startConvoy(p, npc, rd.id(), trade);
            p.sendMessage(result);
        }
        p.closeInventory();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        manager.closeRoutesGui(event.getPlayer().getUniqueId());
    }
}

