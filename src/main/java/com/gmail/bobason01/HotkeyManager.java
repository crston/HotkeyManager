package com.gmail.bobason01;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class HotkeyManager extends JavaPlugin implements Listener {

    private final Map<String, HotkeyAction> hotkeyMap = new HashMap<>();
    private final Map<UUID, Long> lastShiftLPress = new HashMap<>();

    private static final long SHIFT_MEMORY_MS = 600;
    private static final long SHIFT_L_SIMULTANEOUS_MS = 100;
    private static final long GUI_COMMAND_DELAY_TICKS = 1L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadHotkeys();
        getServer().getPluginManager().registerEvents(this, this);
        registerPacketListeners();

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

    private void registerPacketListeners() {
        // L, SHIFT_L 처리
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this,
                ListenerPriority.NORMAL, PacketType.Play.Client.ADVANCEMENTS) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                if (!player.isOnline()) return;

                Bukkit.getScheduler().runTask(HotkeyManager.this, () -> {
                    boolean shift = player.isSneaking();
                    UUID uuid = player.getUniqueId();
                    long now = System.currentTimeMillis();

                    boolean hasL = hotkeyMap.containsKey("L");
                    boolean hasShiftL = hotkeyMap.containsKey("SHIFT_L");

                    if (shift && hasShiftL) {
                        suppressAdvancementGUIOnceBeforeDelay(player, GUI_COMMAND_DELAY_TICKS);
                        handleKeyWithDelay("SHIFT_L", player, GUI_COMMAND_DELAY_TICKS);
                        lastShiftLPress.put(uuid, now);
                    } else if (hasL) {
                        long timeSinceShift = now - lastShiftLPress.getOrDefault(uuid, 0L);
                        if (timeSinceShift < SHIFT_L_SIMULTANEOUS_MS || (timeSinceShift < SHIFT_MEMORY_MS && shift)) {
                            suppressAdvancementGUIOnceBeforeDelay(player, GUI_COMMAND_DELAY_TICKS);
                            return;
                        }
                        suppressAdvancementGUIOnceBeforeDelay(player, GUI_COMMAND_DELAY_TICKS);
                        handleKeyWithDelay("L", player, GUI_COMMAND_DELAY_TICKS);
                    }
                });
            }
        });

        // Q, SHIFT_Q 처리
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this,
                ListenerPriority.NORMAL, PacketType.Play.Client.BLOCK_DIG) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                var action = event.getPacket().getPlayerDigTypes().read(0);

                if (action != EnumWrappers.PlayerDigType.DROP_ITEM && action != EnumWrappers.PlayerDigType.DROP_ALL_ITEMS)
                    return;

                ItemStack item = player.getInventory().getItemInMainHand();
                if (!item.getType().isAir()) return;

                String key = player.isSneaking() ? "SHIFT_Q" : "Q";
                if (!hotkeyMap.containsKey(key)) return;

                Bukkit.getScheduler().runTask(HotkeyManager.this, () -> handleKey(key, player));
            }
        });
    }

    private void suppressAdvancementGUIOnceBeforeDelay(Player player, long delayTicks) {
        closeAdvancementGUI(player);
        long secondDelay = Math.max(0, delayTicks - 1);
        Bukkit.getScheduler().runTaskLater(this, () -> closeAdvancementGUI(player), secondDelay);
    }

    private void closeAdvancementGUI(Player player) {
        try {
            var packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.CLOSE_WINDOW);
            packet.getIntegers().write(0, 0);
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
        } catch (Exception e) {
            getLogger().warning("Failed to close Advancement GUI: " + e.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                var packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.OPEN_WINDOW);
                packet.getIntegers().write(0, 0);
                packet.getStrings().write(0, "minecraft:advancements");
                packet.getChatComponents().write(0, WrappedChatComponent.fromText("Advancements"));
                ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
                closeAdvancementGUI(player);
            } catch (Exception e) {
                getLogger().warning("Failed to open/close Advancement GUI on join: " + e.getMessage());
            }
        }, 20L);
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
        HotkeyAction action = hotkeyMap.get(key);
        if (action == null) return;

        int originalSlot = event.getPreviousSlot();
        action.execute(player);
        Bukkit.getScheduler().runTaskLater(this, () -> player.getInventory().setHeldItemSlot(originalSlot), 1L);
    }

    private void handleKey(String key, Player player) {
        Optional.ofNullable(hotkeyMap.get(key)).ifPresent(action -> action.execute(player));
    }

    private void handleKeyWithDelay(String key, Player player, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(this, () -> handleKey(key, player), delayTicks);
    }

    private record HotkeyAction(String command, String executor) {
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
