# POJO-actor SKILL

任意の Java POJO をアクターとして動作させる軽量ライブラリ。
Java 21 仮想スレッド・ゼロリフレクション・FIFO メッセージ順序保証が設計の柱。

---

## Maven 依存

```xml
<dependency>
    <groupId>com.scivicslab</groupId>
    <artifactId>pojo-actor</artifactId>
    <version>3.0.1</version>
</dependency>
```

Java 21 以上が必須。

---

## 基本パターン

### ActorSystem とアクターの生成

```java
// ActorSystem はアプリケーションで 1 つ作る
ActorSystem system = new ActorSystem("my-system");          // スレッド数 = CPU コア数
ActorSystem system = new ActorSystem("my-system", 4);       // スレッド数を指定
ActorSystem system = new ActorSystem.Builder("my-system").threadNum(8).build();

// 任意の POJO をそのままアクターにする（継承・実装は不要）
ActorRef<Counter>          ref = system.actorOf("counter", new Counter());
ActorRef<ArrayList<String>> ref = system.actorOf("list",    new ArrayList<>());
```

### tell / ask

```java
// tell — Fire and Forget
ref.tell(c -> c.increment());
ref.tell(c -> c.increment()).join();  // 完了を待つ場合

// ask — Request/Response
CompletableFuture<Integer> f = ref.ask(c -> c.getValue());
int value = f.join();
```

| メソッド | キュー | スレッドセーフ | 戻り値 | 用途 |
|---------|-------|-------------|--------|------|
| `tell(action)` | ✓ | 自動 | `CompletableFuture<Void>` | 通常の状態変更 |
| `ask(action)` | ✓ | 自動 | `CompletableFuture<R>` | 通常のデータ取得 |
| `tell(action, pool)` | ✗ | 利用者責任 | `CompletableFuture<Void>` | CPU ヘビーな処理 |
| `ask(action, pool)` | ✗ | 利用者責任 | `CompletableFuture<R>` | CPU ヘビーな処理＋結果取得 |
| `tellNow(action)` | ✗ バイパス | 利用者責任 | `CompletableFuture<Void>` | 緊急停止・優先処理 |
| `askNow(action)` | ✗ バイパス | 利用者責任 | `CompletableFuture<R>` | 監視・デバッグ |

キュー経由のメソッドはアクターの内部スレッドで FIFO 処理されるため、呼び出し側でのロックは不要。

### ライフサイクル

```java
// 状態確認
system.isAlive();              // システム全体
system.isAlive("counter");     // 特定のアクター
ref.isAlive();

// 停止
ref.close();                   // アクター単体
system.terminate();            // システム全体（最大 60 秒待って強制終了）

// try-with-resources でスコープ管理
try (ActorRef<MyActor> actor = new ActorRef<>("temp", new MyActor())) {
    actor.tell(a -> a.doWork()).join();
}

// キューだけクリア（アクターは生かしたまま）
int cleared = ref.clearPendingMessages();
```

---

## CPU ヘビーな処理

アクターのメッセージループ（仮想スレッド）は軽量処理向け。
CPU バウンドな処理はマネージドスレッドプールに委譲する。

```java
ExecutorService pool = system.getManagedThreadPool();
ref.tell(c -> c.heavyCompute(), pool);
double result = ref.ask(c -> c.calculate(), pool).join();

// 複数のプールを使い分けたい場合
system.addManagedThreadPool(8);   // インデックス 1
system.addManagedThreadPool(2);   // インデックス 2
ExecutorService pool1 = system.getManagedThreadPool(1);
```

---

## JsonState — アクター内の動的状態ストア（v2.10.0〜）

各アクターが独立した JSON ストアを持つ。XPath 風のパスで読み書きする。

```java
// 書き込み
ref.tell(a -> a.putJson("workflow.retry", 3));
ref.tell(a -> a.putJson("hosts[0]", "server1.example.com"));

// 読み込み
int    retry = ref.ask(a -> a.getJsonInt("workflow.retry", 0)).join();
String host  = ref.ask(a -> a.getJsonString("hosts[0]")).join();
bool   found = ref.ask(a -> a.hasJson("workflow.retry")).join();

// ワークフロー内での変数展開
// ${result}      → 直前のアクション結果
// ${json.key}    → JsonState の値
String expanded = actor.expandVariables("Host is ${json.hostname}");

// クリア
ref.tell(a -> a.clearJsonState());
```

---

## Scheduler — 定期実行

アクターへのメッセージを定期的に投入する。内部で `ask()` を使うため通常のメッセージと FIFO で直列化される。
詳細は `reference/scheduler.md` を参照。

---

## Accumulator — 結果集約

複数アクターの結果を集めるユーティリティ。`ActorRef` でラップしてスレッドセーフに使う。
詳細は `reference/accumulator.md` を参照。

---

## 子アクター

親アクターが子アクターを管理する構造。処理を分担しつつ、親が子の結果を集約するパターンでよく使う。

```java
ActorRef<Parent> parent = system.actorOf("parent", new Parent());

// 子アクターの生成（ActorSystem に自動登録される）
ActorRef<Child> child1 = parent.createChild("child-1", new Child());
ActorRef<Child> child2 = parent.createChild("child-2", new Child());

// 親子関係の確認
child1.getParentName();          // "parent"
parent.getNamesOfChildren();     // {"child-1", "child-2"}
```

### 典型的な使い方：親が子に仕事を投げて結果を集める

```java
// 子アクターに並列で仕事を投げる
List<CompletableFuture<String>> futures = parent.getNamesOfChildren().stream()
    .map(name -> system.getActor(name, Child.class))
    .map(child -> child.ask(c -> c.process()))
    .toList();

// 全子アクターの完了を待って親が集約
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
List<String> results = futures.stream().map(CompletableFuture::join).toList();
parent.tell(p -> p.aggregate(results));
```

### Accumulator と組み合わせるパターン

```java
ActorRef<Accumulator> acc = system.actorOf("results", new TableAccumulator());

parent.getNamesOfChildren().stream()
    .map(name -> system.getActor(name, Child.class))
    .forEach(child -> child.ask(c -> c.process())
        .thenAccept(result -> acc.tell(a -> a.add(child.getName(), "output", result))));

// 全子アクターの結果が揃うのを待つ
// （全 tell が完了したことを確認してから getSummary する）
String summary = acc.ask(Accumulator::getSummary).join();
```

---

## プラグイン登録（ActorProvider）

外部 JAR からアクターをサービスローダーで自動登録する。実行時ロード（`DynamicActorLoader`）も含め詳細は `reference/plugin.md` を参照。

---

## 分散アクター（DistributedActorSystem）

HPC クラスタや K8s で複数ノードにまたがるアクターシステム。
HTTP（Slurm 等）と Kafka（K8s）の 2 つのトランスポートに対応する。

詳細は `reference/distributed.md` を参照。

---

## よくある間違い

**tell/ask にキャプチャした変数を渡すとき** — ラムダは Serializable でないため、アクター外の変数を直接変更してはいけない。

```java
// NG: 外部変数への副作用
int[] count = {0};
ref.tell(a -> count[0] = a.getValue());  // スレッドセーフでない

// OK: ask で値を受け取る
int value = ref.ask(a -> a.getValue()).join();
```

**CPU ヘビーな処理をキューに流す** — 仮想スレッドを長時間ブロックすると他のアクターのメッセージ処理が遅延する。`tell(action, pool)` で委譲すること。

**terminate() を呼び忘れる** — `ActorSystem` はスレッドプールを保持する。JVM が終了しない場合は必ず `system.terminate()` を呼ぶ。
