package com.yourname.purpuroptimizer;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PurpurAggressiveOptimizer extends JavaPlugin implements Listener, CommandExecutor {

    private double tpsThreshold;
    private double mobAiRadiusSq;
    private int maxMonsters;
    private int maxAnimals;
    private int trashDespawnTicks;
    private final Set<Material> trashItems = EnumSet.noneOf(Material.class);
    private int maxRedstoneUpdates;

    // Bộ nhớ đệm theo dõi hoạt động Redstone nhằm tránh nghẽn RAM (Key: ChunkKey, Value: Số lần update)
    private final Map<Long, Integer> redstoneTickCounter = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // Khởi tạo file config mặc định nếu chưa có
        saveDefaultConfig();
        loadPluginConfig();

        // Đăng ký Event và Command
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("purpuroptimizer")).setExecutor(this);

        // Vòng lặp tối ưu hóa AI của Mob: Chạy mỗi 2 giây (40 ticks)
        Bukkit.getScheduler().runTaskTimer(this, this::optimizeMobAI, 40L, 40L);

        // Vòng lặp reset bộ đếm Redstone: Chạy mỗi giây (20 ticks)
        Bukkit.getScheduler().runTaskTimer(this, redstoneTickCounter::clear, 20L, 20L);

        getLogger().info("=== PurpurAggressiveOptimizer Bản FULL đã kích hoạt! ===");
    }

    private void loadPluginConfig() {
        reloadConfig();
        this.tpsThreshold = getConfig().getDouble("tps-threshold", 17.5);
        double radius = getConfig().getDouble("mob-ai-radius", 24.0);
        this.mobAiRadiusSq = radius * radius;
        this.maxMonsters = getConfig().getInt("max-monsters-per-chunk", 8);
        this.maxAnimals = getConfig().getInt("max-animals-per-chunk", 6);
        this.trashDespawnTicks = (300 - getConfig().getInt("trash-item-despawn-seconds", 30)) * 20;
        this.maxRedstoneUpdates = getConfig().getInt("max-redstone-updates-per-second", 100);

        this.trashItems.clear();
        for (String itemStr : getConfig().getStringList("trash-items")) {
            try {
                this.trashItems.add(Material.valueOf(itemStr.toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    // XỬ LÝ LỆNH: /pao reload
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            loadPluginConfig();
            sender.sendMessage("§a[PAO] Đã tải lại cấu hình plugin thành công!");
            return true;
        }
        sender.sendMessage("§c[PAO] Sử dụng lệnh: /pao reload để làm mới cấu hình.");
        return true;
    }

    // 1. KIỂM SOÁT SỐ LƯỢNG THỰC THỂ (HARD CAP PER CHUNK)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity) || entity instanceof Player || entity instanceof ArmorStand) return;
        if (entity.getCustomName() != null) return;

        Chunk chunk = entity.getLocation().getChunk();
        long monsters = Arrays.stream(chunk.getEntities()).filter(e -> e instanceof Monster && e.getCustomName() == null).count();
        long animals = Arrays.stream(chunk.getEntities()).filter(e -> e instanceof Animals && e.getCustomName() == null).count();

        if (entity instanceof Monster && monsters >= maxMonsters) {
            event.setCancelled(true);
        } else if (entity instanceof Animals && animals >= maxAnimals) {
            if (entity instanceof Tameable && ((Tameable) entity).isTamed()) return;
            event.setCancelled(true);
        }
    }

    // 2. GỘP VÀ XỬ LÝ ITEM RƠI SIÊU NHANH
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item newItem = event.getEntity();
        ItemStack stack = newItem.getItemStack();

        if (stack.hasItemMeta() && (stack.getItemMeta().hasDisplayName() || stack.getItemMeta().hasEnchants())) return;
        if (stack.getType().name().contains("SHULKER_BOX")) return;

        // Ép các item rác biến mất nhanh bằng cách tăng số tick đã sống (ticksLived) của nó lên
        if (trashItems.contains(stack.getType())) {
            newItem.setTicksLived(Math.max(0, newItem.getTicksLived() + trashDespawnTicks));
        }

        // Chạy tác vụ gộp nhanh sau 1 tick để tránh xung đột vật lý
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!newItem.isValid()) return;
            for (Entity nearby : newItem.getNearbyEntities(5, 5, 5)) {
                if (nearby instanceof Item && nearby.isValid()) {
                    Item oldItem = (Item) nearby;
                    if (oldItem.getItemStack().isSimilar(stack)) {
                        int total = oldItem.getItemStack().getAmount() + stack.getAmount();
                        if (total <= 64) {
                            oldItem.getItemStack().setAmount(total);
                            newItem.remove();
                            return;
                        }
                    }
                }
            }
        }, 1L);
    }

    // 3. CHỐNG MÁY LAG REDSTONE (ANTI REDSTONE LAG MACHINE)
    @EventHandler(priority = EventPriority.NORMAL)
    public void onRedstoneUpdate(BlockRedstoneEvent event) {
        Chunk chunk = event.getBlock().getChunk();
        long chunkKey = chunk.getChunkKey();

        int currentUpdates = redstoneTickCounter.getOrDefault(chunkKey, 0);

        if (currentUpdates > maxRedstoneUpdates) {
            event.setNewCurrent(event.getOldCurrent()); // Khóa dòng điện lại, đóng băng mạch Redstone quá tải
            return;
        }

        redstoneTickCounter.put(chunkKey, currentUpdates + 1);
    }

    // 4. THUẬT TOÁN QUẢN LÝ DYN-AI (DYNAMIC AI LIMITER)
    private void optimizeMobAI() {
        double currentTPS = Bukkit.getTPS()[0];
        // Nếu TPS sụt giảm sâu, siết chặt bán kính tắt AI của quái vật lại gần người chơi hơn
        double activeRadiusSq = (currentTPS < tpsThreshold) ? (mobAiRadiusSq / 2.0) : mobAiRadiusSq;

        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (!entity.isValid() || entity instanceof Player || entity instanceof ArmorStand) continue;

                // Các bộ lọc an toàn cho thực thể đặc biệt
                if (entity.getCustomName() != null || !entity.getPassengers().isEmpty()) { toggleAI(entity, true); continue; }
                if (entity instanceof Tameable && ((Tameable) entity).isTamed()) { toggleAI(entity, true); continue; }
                if (entity instanceof Villager && ((Villager) entity).getVillagerLevel() > 1) { toggleAI(entity, true); continue; }

                boolean playerNearby = false;
                for (Player player : world.getPlayers()) {
                    if (player.getLocation().distanceSquared(entity.getLocation()) < activeRadiusSq) {
                        playerNearby = true;
                        break;
                    }
                }

                toggleAI(entity, playerNearby);
            }
        }
    }

    private void toggleAI(LivingEntity entity, boolean enable) {
        if (enable && !entity.hasAI()) {
            entity.setAI(true);
        } else if (!enable && entity.hasAI()) {
            entity.setAI(false); // Đóng băng thực thể ở xa để giải phóng CPU
        }
    }
}
