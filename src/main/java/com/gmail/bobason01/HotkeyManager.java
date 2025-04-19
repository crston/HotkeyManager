package com.gmail.bobason01;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class HotkeyManager extends JavaPlugin implements Listener {

    private final Map<String, HotkeyAction> hotkeyMap = new HashMap<>();
    private final Map<UUID, Long> lCooldowns = new HashMap<>();
    private final long lCooldownMillis = 500; // 0.5초 쿨타임

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadHotkeys();
        getServer().getPluginManager().registerEvents(this, this);
        registerAdvancementListener();

        Objects.requireNonNull(getCommand("hotkeyreload")).setExecutor((sender, command, label, args) -> {
            reloadConfig();
            loadHotkeys();
            sender.sendMessage("§aHotkeyManager config reloaded.");
            return true;
        });
    }

    private void loadHotkeys() {
        hotkeyMap.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("hotkeys");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null || !s.getBoolean("enabled", true)) continue;
            String command = s.getString("command", "");
            String executor = s.getString("executor", "player").toLowerCase();
            hotkeyMap.put(key.toUpperCase(Locale.ROOT), new HotkeyAction(command, executor));
        }
    }

    private void registerAdvancementListener() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this,
                ListenerPriority.NORMAL, PacketType.Play.Client.ADVANCEMENTS) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                boolean sneaking = player.isSneaking();

                if (sneaking) {
                    if (!hotkeyMap.containsKey("SHIFT_L")) return;
                    Bukkit.getScheduler().runTask(HotkeyManager.this, () -> {
                        lCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
                        handleKey("SHIFT_L", player);
                    });
                } else {
                    if (!hotkeyMap.containsKey("L")) return;
                    Long last = lCooldowns.get(player.getUniqueId());
                    if (last != null && System.currentTimeMillis() - last < lCooldownMillis) return;
                    Bukkit.getScheduler().runTask(HotkeyManager.this, () -> handleKey("L", player));
                }
            }
        });
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        String key = player.isSneaking() ? "SHIFT_Q" : "Q";

        if (!hotkeyMap.containsKey(key)) return;

        event.setCancelled(true);
        handleKey(key, player);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        String key = player.isSneaking() ? "SHIFT_F" : "F";

        if (!hotkeyMap.containsKey(key)) return;

        event.setCancelled(true);
        handleKey(key, player);
    }

    @EventHandler
    public void onHotbarChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking()) return;

        String key = "SHIFT_" + (event.getNewSlot() + 1);
        if (!hotkeyMap.containsKey(key)) return;

        handleKey(key, player);
    }

    private void handleKey(String key, Player player) {
        HotkeyAction action = hotkeyMap.get(key.toUpperCase(Locale.ROOT));
        if (action == null) return;

        if (player.isOnline()) {
            player.closeInventory(); // 인벤토리 열려있으면 닫기
        }

        action.execute(player);
    }

    private static class HotkeyAction {
        private final String command;
        private final String executor;

        public HotkeyAction(String command, String executor) {
            this.command = command;
            this.executor = executor;
        }

        public void execute(Player player) {
            String parsedCommand = command.replace("%player%", player.getName());
            switch (executor) {
                case "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand);
                case "op" -> {
                    boolean wasOp = player.isOp();
                    player.setOp(true);
                    player.performCommand(parsedCommand);
                    player.setOp(wasOp);
                }
                default -> player.performCommand(parsedCommand);
            }
        }
    }
}
