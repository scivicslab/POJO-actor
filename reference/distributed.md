# 分散アクター — DistributedActorSystem

HPC クラスタや K8s で複数ノードにまたがるアクターシステムを構築する。

---

## 前提

ノード間通信にトランスポートを選ぶ。

| トランスポート | 向いている環境 | 依存 |
|-------------|-------------|------|
| `HttpTransport` | Slurm / Grid Engine など HPC クラスタ | 標準（追加不要） |
| `KafkaTransport` | Kubernetes | `kafka-clients:3.7.0`（optional） |

---

## ノード検出

```java
// 実行環境を自動判定（Slurm / K8s / GridEngine）
NodeDiscovery discovery = NodeDiscoveryFactory.autoDetect();
```

| 実装クラス | 検出方法 |
|-----------|---------|
| `SlurmNodeDiscovery` | 環境変数 `SLURM_JOB_NODELIST` |
| `K8sNodeDiscovery` | `POD_NAME` + `KUBERNETES_SERVICE_HOST` |
| `GridEngineNodeDiscovery` | `PE_HOSTFILE` |

---

## HTTP トランスポート（HPC クラスタ向け）

```java
NodeDiscovery discovery = NodeDiscoveryFactory.autoDetect();

DistributedActorSystem dist = DistributedActorSystem.builder()
    .localActorSystem(new ActorSystem("node"))
    .transport(new HttpTransport())
    .discovery(discovery)
    .build();

dist.startHttpServer(8080);

// リモートアクターを呼ぶ
RemoteActorRef remote = dist.remoteActorOf(dist.getNodes().get(1), "my-actor");
ActionResult result = remote.callByActionName("process", payload);

dist.close();
```

---

## Kafka トランスポート（K8s 向け）

```java
NodeDiscovery discovery = NodeDiscoveryFactory.autoDetect();

KafkaTransport transport = new KafkaTransport(
    new NodeInfo(discovery.getMyNodeId(), discovery.getMyHost(), discovery.getMyPort()),
    "kafka:9092"
);

DistributedActorSystem dist = DistributedActorSystem.builder()
    .localActorSystem(new ActorSystem("node"))
    .transport(transport)
    .discovery(discovery)
    .build();

dist.startServer("kafka:9092");

RemoteActorRef remote = dist.remoteActorOf(dist.getNodes().get(1), "my-actor");
ActionResult result = remote.callByActionName("process", payload);

dist.close();
```

---

## CallableByActionName — リモート呼び出しを受け付けるアクター

`RemoteActorRef.callByActionName()` で呼ばれる側のアクターに必要。
ローカルの `ActorRef.tell/ask` だけ使う場合は実装不要。

```java
public class MyActor implements CallableByActionName {
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        return switch (actionName) {
            case "process" -> new ActionResult(true, doProcess(args));
            default        -> new ActionResult(false, "Unknown: " + actionName);
        };
    }
}
```

`ActionResult` は `(boolean success, String result)` のペア。

---

## 主要クラス一覧

| クラス | パッケージ | 役割 |
|--------|----------|------|
| `DistributedActorSystem` | `core.distributed` | ローカル ActorSystem に分散機能を追加 |
| `RemoteActorRef` | `core.distributed` | リモートノードのアクターへの参照 |
| `ActorMessage` | `core.distributed` | 分散メッセージのデータクラス |
| `NodeInfo` | `core.distributed` | ノード情報（nodeId, host, port） |
| `HttpActorServer` | `core.distributed` | HTTP メッセージ受信サーバ |
| `KafkaActorServer` | `core.distributed` | Kafka メッセージ受信サーバ |
| `NodeDiscoveryFactory` | `core.distributed.discovery` | 環境自動判定ファクトリ |
| `HttpTransport` | `core.distributed.transport` | HTTP トランスポート実装 |
| `KafkaTransport` | `core.distributed.transport` | Kafka トランスポート実装 |
