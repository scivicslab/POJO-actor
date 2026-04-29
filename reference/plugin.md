# プラグイン登録 — ActorProvider

外部 JAR からアクターをサービスローダーで自動登録する仕組み。

---

## 実装

```java
public class MyProvider implements ActorProvider {
    @Override
    public void registerActors(ActorSystem system) {
        system.actorOf("myActor", new MyActor());
    }
}
```

```
# META-INF/services/com.scivicslab.pojoactor.core.ActorProvider
com.example.MyProvider
```

JAR をクラスパスに追加するだけで `ActorSystem` 起動時に自動的に `registerActors()` が呼ばれる。

---

## DynamicActorLoader — 実行時ロード

起動後に外部 JAR を動的にロードしてアクターを追加する。
Turing Workflow の `loader.loadJar` / `loader.createChild` はこの仕組みを使っている。

```java
DynamicActorLoader loader = new DynamicActorLoader(system);

// Maven ローカルリポジトリから JAR をロード
loader.loadJar("com.example:my-plugin:1.0.0");

// ロードした JAR のクラスからアクターを生成
ActorRef<?> actor = loader.createActor("myActor", "com.example.MyActor");
```
