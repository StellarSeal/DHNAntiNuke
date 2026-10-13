package me.stella.antinuke;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import me.stella.DHNAntiNuke;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Anti-Nuke listener — đếm gói đào block của từng người chơi.
 * <p>
 * Đếm số gói bắt đầu đào block (START_DIGGING) của từng người chơi mỗi giây,
 * vượt giới hạn → hủy gói tin âm thầm (không gửi thông báo cho người chơi).
 * <p>
 * onPacketReceiving chạy trên thread Netty của ProtocolLib (off-main),
 * agent reset counter chạy trên ExecutorService riêng của plugin.
 */
public final class AntiNukeListener extends PacketAdapter implements Listener {

    // ponytail: chỉ đếm START_DESTROY_BLOCK — tên enum cũ của ProtocolLib (START_DIGGING)
    // không thể hiện đúng nghĩa, PL 5.x đã đổi tên trùng khớp NMS.
    // Nếu sau này cần đếm cả STOP_DESTROY_BLOCK → đổi thành Set.of(START_DESTROY_BLOCK, STOP_DESTROY_BLOCK)
    // và nhân đôi limit.
    private static final EnumWrappers.PlayerDigType COUNTED_ACTION = EnumWrappers.PlayerDigType.START_DESTROY_BLOCK;

    /** Số tick chờ trước khi gửi lại block thật cho client — đủ lâu để client
     *  kịp xong animation phá block (prediction), đủ ngắn để không thấy lag. */
    private static final long GHOST_FIX_DELAY_TICKS = 3L;

    private final DHNAntiNuke plugin;
    private final Map<UUID, PlayerAgent> agents = new ConcurrentHashMap<>();

    public AntiNukeListener(DHNAntiNuke plugin) {
        super(plugin, ListenerPriority.HIGHEST, PacketType.Play.Client.BLOCK_DIG);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        if (plugin.isShuttingDown()) return;
        Player player = event.getPlayer();
        if (player == null) return;
        // ProtocolLib mapping (EnumWrappers) → đa phiên bản, không chạm NMS
        if (event.getPacket().getPlayerDigTypes().readSafely(0) != COUNTED_ACTION) return;
        if (player.hasPermission("dhnantinuke.bypass")) return;
        PlayerAgent agent = agents.computeIfAbsent(player.getUniqueId(), id -> new PlayerAgent());
        if (agent.dig() > plugin.currentLimit()) {
            event.setCancelled(true); // chặn âm thầm — không thông báo cho người chơi
            // Client vẫn prediction phá block dù gói bị hủy → gửi lại block thật
            // vài tick sau để không còn "ghost block" (client thấy air, server còn block).
            BlockPosition pos = event.getPacket().getBlockPositionModifier().readSafely(1);
            if (pos != null) agent.ghost(player, pos);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        PlayerAgent agent = agents.remove(event.getPlayer().getUniqueId());
        if (agent != null) agent.kill();
    }

    /** Gỡ listener + dọn toàn bộ agent. Idempotent, gọi từ onDisable. */
    public void shutdown() {
        try {
            ProtocolLibrary.getProtocolManager().removePacketListener(this);
        } catch (Throwable ignored) {
            // ProtocolLib đã tắt trước (thứ tự reload) — không sao
        }
        HandlerList.unregisterAll(this);
        List<PlayerAgent> alive = new ArrayList<>(agents.values());
        agents.clear();
        alive.forEach(PlayerAgent::kill);
    }

    /**
     * Agent theo từng người chơi — chạy trên ExecutorService riêng của plugin
     * (không phải Bukkit async scheduler). Counter tự reset về 0 mỗi giây
     * bằng 1 task định kỳ trên executor.
     */
    final class PlayerAgent {

        private final AtomicInteger digs = new AtomicInteger();
        private final ScheduledFuture<?> reset;

        /** Vị trí block bị hủy gói (packed long) chờ gửi lại cho client. */
        private final Set<Long> pendingGhostBlocks = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean ghostFlushScheduled = new AtomicBoolean();

        PlayerAgent() {
            ScheduledFuture<?> task;
            try {
                task = plugin.agentExecutor()
                        .scheduleAtFixedRate(() -> digs.set(0), 1L, 1L, TimeUnit.SECONDS);
            } catch (RejectedExecutionException executorClosed) {
                task = null; // plugin đang tắt — agent mồ côi, không lên lịch được
            }
            this.reset = task;
        }

        int dig() {
            return digs.incrementAndGet();
        }

        /**
         * Ghi nhận 1 block bị chặn (gói đào đã hủy) — Netty thread gọi.
         * Gộp các vị trí vào 1 task sync duy nhất /player, chạy vài tick sau,
         * gửi lại block thật cho client (chống ghost block). Dedupe bằng set:
         * nuker spam cùng 1 vị trí → vẫn chỉ 1 lần gửi.
         */
        void ghost(Player player, BlockPosition pos) {
            pendingGhostBlocks.add(pack(pos));
            if (!ghostFlushScheduled.compareAndSet(false, true)) return;
            try {
                plugin.getServer().getScheduler()
                        .runTaskLater(plugin, () -> flushGhostBlocks(player), GHOST_FIX_DELAY_TICKS);
            } catch (IllegalPluginAccessException pluginDisabled) {
                ghostFlushScheduled.set(false);
                pendingGhostBlocks.clear();
            }
        }

        /** Main thread: gửi lại block thật cho từng vị trí đã chặn. */
        private void flushGhostBlocks(Player player) {
            ghostFlushScheduled.set(false);
            if (plugin.isShuttingDown()) {
                pendingGhostBlocks.clear();
                return;
            }
            for (Iterator<Long> it = pendingGhostBlocks.iterator(); it.hasNext(); ) {
                long packed = it.next();
                it.remove();
                if (!player.isOnline()) continue;
                int x = (int) (packed >> 38);
                int z = (int) ((packed << 26) >> 38);
                int y = (short) ((packed << 52) >> 52);
                Block block = player.getWorld().getBlockAt(x, y, z);
                player.sendBlockChange(block.getLocation(), block.getBlockData());
            }
        }

        void kill() {
            if (reset != null) reset.cancel(false);
            pendingGhostBlocks.clear();
        }

        /** ponytail: packing thủ công (x:26 bit, z:26 bit, y:12 bit) — đủ cho tọa độ Minecraft.
         *  Nếu sau này cần lưu thêm world/blockData → thay bằng record nhỏ. */
        private static long pack(BlockPosition pos) {
            return ((long) (pos.getX() & 0x3FFFFFF) << 38)
                    | ((long) (pos.getZ() & 0x3FFFFFF) << 12)
                    | (pos.getY() & 0xFFF);
        }
    }
}
