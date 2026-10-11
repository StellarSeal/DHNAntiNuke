# DHNAntiNuke

Addon chống nuke (đào block hàng loạt) cho server Minecraft.

## Tính năng

- **Đếm gói `START_DESTROY_BLOCK`** mỗi giây cho từng người chơi (qua ProtocolLib mapping — hỗ trợ đa phiên bản, không dùng NMS).
- **Chặn âm thầm**: vượt giới hạn → hủy gói tin, người chơi không nhận thông báo nào. Tin nhắn chỉ xuất hiện khi khởi động / reload plugin.
- **Giới hạn cố định**: `blocks-per-second` (mặc định 20).
- **Giới hạn động theo TPS** (tùy chọn): bảng ngưỡng `ngưỡng-TPS: giới-hạn` — TPS tụt dưới ngưỡng nào thì siết theo giới hạn đó. Cần ít nhất 1 ngưỡng, không có thì chế độ tự tắt.
- **Async-first**: packet xử lý trên thread Netty, agent chạy trên `ExecutorService` riêng (không dùng Bukkit async scheduler). Main thread gần như trống — chi phí vận hành ~0.
- **Dọn dẹp sạch sẽ** khi tắt/reload (Plugman): executor `shutdownNow` + chờ hết task, không để lại thread rác.

## Cài đặt

1. Server cần **ProtocolLib** (5.4.0+).
2. Thả `DHNAntiNuke.jar` vào thư mục `plugins/`, restart.
3. Chỉnh `plugins/DHNAntiNuke/config.yml` (có hướng dẫn chi tiết tiếng Việt trong file), reload plugin hoặc restart.

## Cấu hình

```yaml
anti-nuke:
  # Số block tối đa được đào mỗi giây (mỗi người chơi)
  blocks-per-second: 20

  tps-dynamic-limit:
    # Bật giới hạn động theo TPS (ghi đè blocks-per-second khi có ngưỡng)
    enabled: false
    # TPS tụt dưới ngưỡng → áp giới hạn tương ứng
    thresholds:
      19: 15
      16: 10
      12: 5
```

## Quyền

| Quyền | Mặc định | Mô tả |
|---|---|---|
| `dhnantinuke.bypass` | OP | Không bị giới hạn đào block |

## Build

```
mvn clean package
```

Yêu cầu Java 21. Kết quả: `target/DHNAntiNuke-<version>.jar`.
