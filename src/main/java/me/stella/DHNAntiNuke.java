package me.stella;

import com.comphenix.protocol.ProtocolLibrary;
import me.stella.antinuke.AntiNukeListener;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.TreeMap;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * DHNAntiNuke — chặn nuke (đào block hàng loạt) bằng cách đếm gói
 * START_DESTROY_BLOCK của từng người chơi mỗi giây.
 * <p>
 * Kiến trúc async-first:
 * <ul>
 *   <li>Packet xử lý trên thread Netty của ProtocolLib (không chạm main thread).</li>
 *   <li>Agent theo người chơi chạy trên {@link ScheduledThreadPoolExecutor} riêng,
 *       không dùng Bukkit async scheduler.</li>
 *   <li>Main thread chỉ có: 1 task đo TPS/giây (nếu bật chế độ động) + PlayerQuitEvent.</li>
 * </ul>
 */
public final class DHNAntiNuke extends JavaPlugin {

    private static final int BOX_WIDTH = 44;
    private static final long SHUTDOWN_GRACE_SECONDS = 5L;

    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    private volatile int baseLimit = 20;
    private volatile boolean tpsDynamic = false;
    private volatile int currentLimit = 20;
    /** Ngưỡng TPS → giới hạn, sắp xếp tăng dần. Chỉ ghi khi load cấu hình. */
    private volatile TreeMap<Double, Integer> thresholds = new TreeMap<>();

    private ScheduledThreadPoolExecutor agentExecutor;
    private AntiNukeListener listener;
    private BukkitTask tpsSampler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        this.agentExecutor = new ScheduledThreadPoolExecutor(1, threadFactory());
        // Task bị cancel rời khỏi queue ngay lập tức → reload (Plugman) không để lại rác
        agentExecutor.setRemoveOnCancelPolicy(true);

        this.listener = new AntiNukeListener(this);
        ProtocolLibrary.getProtocolManager().addPacketListener(listener);
        Bukkit.getPluginManager().registerEvents(listener, this);

        if (tpsDynamic) startTpsSampler();
        printBanner();
    }

    @Override
    public void onDisable() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        if (listener != null) listener.shutdown();
        if (tpsSampler != null) tpsSampler.cancel();
        closeExecutor();
        getLogger().info("Đã dừng DHNAntiNuke — agent executor đã dọn sạch, sẵn sàng reload.");
    }

    // ───────────────────────── Cấu hình ─────────────────────────

    private void loadSettings() {
        FileConfiguration cfg = getConfig();
        this.baseLimit = Math.max(1, cfg.getInt("anti-nuke.blocks-per-second", 20));
        this.thresholds = loadThresholds();
        this.tpsDynamic = cfg.getBoolean("anti-nuke.tps-dynamic-limit.enabled", false);
        if (tpsDynamic && thresholds.isEmpty()) {
            this.tpsDynamic = false;
            getLogger().warning("tps-dynamic-limit đang BẬT nhưng không có ngưỡng hợp lệ nào — "
                    + "tự động tắt chế độ động, dùng blocks-per-second (" + baseLimit + ").");
        }
        updateLimit(20.0);
    }

    private TreeMap<Double, Integer> loadThresholds() {
        TreeMap<Double, Integer> map = new TreeMap<>();
        ConfigurationSection section = getConfig()
                .getConfigurationSection("anti-nuke.tps-dynamic-limit.thresholds");
        if (section == null) return map;
        for (String key : section.getKeys(false)) {
            try {
                double threshold = Double.parseDouble(key);
                if (threshold <= 0) {
                    getLogger().warning("Bỏ ngưỡng TPS '" + key + "' — ngưỡng phải lớn hơn 0.");
                    continue;
                }
                map.put(threshold, Math.max(1, section.getInt(key)));
            } catch (NumberFormatException invalid) {
                getLogger().warning("Bỏ ngưỡng TPS '" + key + "' — không phải số hợp lệ "
                        + "(ví dụ đúng: 19 hoặc 16.5).");
            }
        }
        return map;
    }

    private void updateLimit(double tps) {
        if (!tpsDynamic) {
            this.currentLimit = baseLimit;
            return;
        }
        // Lấy ngưỡng NHỎ NHẤT vẫn cao hơn TPS hiện tại → giới hạn tương ứng.
        // TPS còn cao hơn mọi ngưỡng → dùng mức gốc blocks-per-second.
        Map.Entry<Double, Integer> rule = thresholds.higherEntry(tps);
        this.currentLimit = rule != null ? rule.getValue() : baseLimit;
    }

    // ───────────────────── Giới hạn động theo TPS ─────────────────────

    /**
     * 1 task duy nhất trên main thread, chạy mỗi giây, chỉ trừ nanoTime
     * rồi ghi 1 volatile — chi phí gần bằng 0. Chỉ tồn tại khi bật tps-dynamic-limit.
     */
    private void startTpsSampler() {
        final long[] last = {System.nanoTime()};
        this.tpsSampler = Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.nanoTime();
            double tps = Math.min(20.0, 20_000_000_000.0 / Math.max(1L, now - last[0]));
            last[0] = now;
            updateLimit(tps);
        }, 20L, 20L);
    }

    // ───────────────────── Dọn dẹp executor ─────────────────────

    private void closeExecutor() {
        if (agentExecutor == null) return;
        agentExecutor.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_GRACE_SECONDS);
        try {
            if (!agentExecutor.awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                getLogger().warning("Agent executor chưa dừng hết sau " + SHUTDOWN_GRACE_SECONDS
                        + " giây — có thể còn thread treo.");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger seq = new AtomicInteger(1);
        return task -> {
            Thread thread = new Thread(task, "DHNAntiNuke-Agent-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    // ───────────────────────── Trạng thái cho listener ─────────────────────────

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    public int currentLimit() {
        return currentLimit;
    }

    public ScheduledThreadPoolExecutor agentExecutor() {
        return agentExecutor;
    }

    // ───────────────────────── Banner khởi động ─────────────────────────

    private void printBanner() {
        Logger log = getLogger();
        String mode = tpsDynamic ? "động theo TPS, " + thresholds.size() + " ngưỡng" : "cố định";
        log.info("╔" + "═".repeat(BOX_WIDTH) + "╗");
        log.info(row("DHNAntiNuke v" + getDescription().getVersion() + " — đã kích hoạt"));
        log.info(row("Chống nuke · chặn đào block hàng loạt"));
        log.info("╠" + "═".repeat(BOX_WIDTH) + "╣");
        log.info(row("Giới hạn : " + currentLimit + " block/giây (" + mode + ")"));
        log.info(row("Đếm gói  : START_DESTROY_BLOCK (ProtocolLib mapping)"));
        log.info(row("Kiến trúc : async-first · agent executor riêng"));
        log.info("╚" + "═".repeat(BOX_WIDTH) + "╝");
    }

    private static String row(String text) {
        return "║  " + text + " ".repeat(Math.max(0, BOX_WIDTH - 2 - text.length())) + "║";
    }
}
