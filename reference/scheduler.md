# Scheduler — 定期実行

アクターへのメッセージを定期的に投入するスケジューラ。
内部で `ask()` を使うため、通常のメッセージと FIFO で直列化される。

---

## 基本

```java
Scheduler scheduler = new Scheduler();     // デフォルト 2 スレッド
Scheduler scheduler = new Scheduler(4);    // スレッド数指定

// 固定間隔（前回開始 → 次回開始）
scheduler.scheduleAtFixedRate("health", ref, a -> a.check(), 0, 10, TimeUnit.SECONDS);

// 固定遅延（前回終了 → 次回開始）
scheduler.scheduleWithFixedDelay("cleanup", ref, a -> a.cleanup(), 60, 300, TimeUnit.SECONDS);

// 1 回のみ
scheduler.scheduleOnce("init", ref, a -> a.init(), 5, TimeUnit.SECONDS);

// 管理
scheduler.cancelTask("health");
boolean active = scheduler.isScheduled("health");
int count      = scheduler.getScheduledTaskCount();

scheduler.close();  // AutoCloseable
```

---

## scheduleAtFixedRate vs scheduleWithFixedDelay

| メソッド | 次回起動タイミング | 処理が period を超えた場合 |
|---------|----------------|------------------------|
| `scheduleAtFixedRate` | 前回**開始**から period 後 | 遅延なく即座に次回を起動 |
| `scheduleWithFixedDelay` | 前回**終了**から delay 後 | delay 分待ってから次回を起動 |

処理時間が不安定な場合は `scheduleWithFixedDelay` の方が詰まりにくい。
