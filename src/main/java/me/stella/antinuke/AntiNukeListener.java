package me.stella.antinuke;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import me.stella.DHNAntiNuke;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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

        void kill() {
            if (reset != null) reset.cancel(false);
        }
    }
}
