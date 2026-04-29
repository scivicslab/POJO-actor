# Accumulator — 結果集約

複数のアクターやノードから届く結果を集めるユーティリティ。
`ActorRef` でラップしてスレッドセーフに使うのが基本パターン。

---

## 実装の選択

| クラス | 出力形式 |
|--------|---------|
| `StreamingAccumulator` | 届いたそばからリアルタイム出力 |
| `BufferedAccumulator` | 全収集後にソース別出力 |
| `TableAccumulator` | ソース=行・タイプ=列のテーブル出力 |
| `JsonAccumulator` | JSON 形式出力 |

---

## 基本的な使い方

```java
ActorRef<Accumulator> results = system.actorOf("results", new TableAccumulator());

// 結果を投入（スレッドセーフ）
results.tell(a -> a.add("worker-1", "cpu",    "Intel Xeon"));
results.tell(a -> a.add("worker-1", "memory", "64GB"));
results.tell(a -> a.add("worker-2", "cpu",    "AMD EPYC"));

// 集約結果を取得
String summary = results.ask(Accumulator::getSummary).join();
int    count   = results.ask(Accumulator::getCount).join();

results.tell(Accumulator::clear);
```

---

## 子アクターと組み合わせるパターン

```java
ActorRef<Accumulator> acc = system.actorOf("results", new TableAccumulator());

parent.getNamesOfChildren().stream()
    .map(name -> system.getActor(name, Child.class))
    .forEach(child ->
        child.ask(c -> c.process())
             .thenAccept(result -> acc.tell(a -> a.add(child.getName(), "output", result)))
    );

// 全子アクターの結果が揃うまで待つには CompletableFuture.allOf を使う
String summary = acc.ask(Accumulator::getSummary).join();
```

---

## TableAccumulator の列幅指定

```java
new TableAccumulator()     // デフォルト列幅
new TableAccumulator(40)   // 列幅 40 文字
```
