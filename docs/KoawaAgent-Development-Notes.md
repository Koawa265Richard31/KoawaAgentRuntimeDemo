# KoawaAgent 开发与面试笔记

本文档按开发切片持续记录 KoawaAgent Runtime 的实现过程。

每次记录统一包含：

1. 本次目标
2. 核心代码
3. 执行流程
4. 工程设计与取舍
5. 测试与验收结果
6. 成熟框架对照
7. 面试问题与参考回答
8. 对应提交

---

## 2026-07-24：任务生命周期状态机

### 1. 本次目标

为后续 Checkpoint、Human-in-the-loop 和工具审批建立统一的任务生命周期语义。

本切片只定义领域模型和合法迁移规则，不连接数据库，也不修改 `AgentLoopRunner`。

### 2. 核心代码

实现文件：

- `src/main/java/com/koawa/agent/agent/domain/AgentTaskStatus.java`
- `src/test/java/com/koawa/agent/agent/domain/AgentTaskStatusTest.java`

任务状态：

```java
public enum AgentTaskStatus {
    RUNNING,
    WAITING_FOR_INPUT,
    WAITING_FOR_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
```

核心迁移判断：

```java
public boolean canTransitionTo(AgentTaskStatus target) {
    if (target == null) {
        return false;
    }
    if (this == target) {
        return true;
    }

    return switch (this) {
        case RUNNING ->
                target == WAITING_FOR_INPUT
                        || target == WAITING_FOR_APPROVAL
                        || target == COMPLETED
                        || target == FAILED
                        || target == CANCELLED
                        || target == TIMED_OUT;
        case WAITING_FOR_INPUT, WAITING_FOR_APPROVAL ->
                target == RUNNING
                        || target == CANCELLED
                        || target == TIMED_OUT;
        case COMPLETED, FAILED, CANCELLED, TIMED_OUT -> false;
    };
}
```

### 3. 执行流程

正常执行：

```text
RUNNING -> COMPLETED
```

等待用户补充信息：

```text
RUNNING -> WAITING_FOR_INPUT -> RUNNING -> COMPLETED
```

等待人工审批：

```text
RUNNING -> WAITING_FOR_APPROVAL -> RUNNING -> COMPLETED
```

异常结束：

```text
RUNNING -> FAILED
RUNNING -> CANCELLED
RUNNING -> TIMED_OUT
```

终态不能迁移到其他状态。

### 4. 工程设计与取舍

#### `AgentTaskStatus` 与 `AgentStopReason` 分离

- `AgentStopReason` 描述某一次 Agent Loop 为什么停止。
- `AgentTaskStatus` 描述整个任务是否还能继续或恢复。

例如，Agent 为了向用户追问而退出当前循环：

```text
stopReason = ASK_CLARIFICATION
taskStatus = WAITING_FOR_INPUT
```

这次循环已经停止，但任务还没有结束。

#### 允许保持相同状态

所有状态都允许迁移到自身。执行期间可能连续写入多个 Checkpoint：

```text
RUNNING -> RUNNING
```

这代表重复保存当前生命周期阶段，不代表重新启动任务。

#### 等待态必须先恢复为运行态

不允许：

```text
WAITING_FOR_INPUT -> COMPLETED
```

要求：

```text
WAITING_FOR_INPUT -> RUNNING -> COMPLETED
```

这样可以明确记录恢复时间、恢复输入和恢复后的执行过程。

### 5. 测试与验收结果

测试采用迁移矩阵，而不是只验证少量示例：

```java
for (AgentTaskStatus source : AgentTaskStatus.values()) {
    for (AgentTaskStatus target : AgentTaskStatus.values()) {
        assertEquals(
                ALLOWED_TRANSITIONS.get(source).contains(target),
                source.canTransitionTo(target));
    }
}
```

覆盖内容：

- 全部 `7 × 7` 种状态组合
- 等待态分类
- 终态分类
- `null` 目标状态
- 迁移矩阵是否覆盖全部枚举值

验收结果：

```text
tests=63
failures=0
errors=0
skipped=0
```

### 6. 成熟框架对照

当前设计借鉴工作流和 Agent 框架中常见的三层语义：

```text
运行状态 -> 可恢复的暂停状态 -> 不可恢复的终态
```

本项目暂时不直接绑定具体框架的数据结构，而是先建立自己的领域模型。后续接入 Checkpoint 存储时，再比较 LangGraph 的 thread、checkpoint 和 interrupt 语义。

### 7. 面试问题与参考回答

#### 问题一：为什么不直接用 `AgentStopReason` 表示任务状态？

参考回答：

停止原因描述一次循环事件，任务状态描述长期生命周期。Agent 因为等待用户输入而停止当前循环时，任务仍然可以恢复，因此不能把它建模成已经完成或失败。

#### 问题二：为什么把状态迁移规则放进枚举？

参考回答：

当前规则规模较小，而且属于领域不变量。放在领域对象中，可以避免 Controller、Service 和持久化层分别实现一套判断。未来如果迁移依赖权限、审批记录或版本信息，再提取为独立的策略服务。

#### 问题三：为什么终态不允许恢复？

参考回答：

终态保持单调性，可以让审计、指标统计和幂等控制更可靠。如果需要重试失败任务，应创建新的执行尝试或任务版本，而不是直接篡改原任务的终态。

#### 问题四：为什么允许状态迁移到自身？

参考回答：

同状态迁移用于支持幂等保存。例如 Agent 在运行过程中可以连续保存多个 Checkpoint，这些保存不会改变任务的生命周期阶段。

#### 问题五：为什么等待态不能直接变成完成态？

参考回答：

等待态恢复后先进入 `RUNNING`，可以明确记录恢复动作、恢复输入和恢复后的执行过程。如果直接进入完成态，会跳过实际执行阶段，降低可观测性和审计能力。

### 8. 对应提交

```text
4dddcaf Add agent task lifecycle model
```

---

## 2026-07-24：不可变任务快照

### 1. 本次目标

定义在 Step 边界保存和恢复 Agent 任务所需的数据契约。

本切片只实现不可变 Snapshot 和领域不变量，不实现数据库、Checkpoint Store 或
`AgentState` 映射。

### 2. 核心代码

实现文件：

- `src/main/java/com/koawa/agent/agent/domain/AgentTaskSnapshot.java`
- `src/test/java/com/koawa/agent/agent/domain/AgentTaskSnapshotTest.java`

核心结构：

```java
public record AgentTaskSnapshot(
        int schemaVersion,
        String taskId,
        String conversationId,
        String userId,
        long revision,
        AgentTaskStatus status,
        String originalQuestion,
        int nextStep,
        int maxSteps,
        Instant deadlineAt,
        List<StepSnapshot> steps,
        List<MessageSnapshot> historySnapshot,
        Map<String, String> recoveryContext,
        PendingInterrupt pendingInterrupt,
        Instant createdAt,
        Instant updatedAt
) {
}
```

Snapshot 内部使用三个持久化值对象：

- `StepSnapshot`：已完成步骤以及 Action/Observation 数据。
- `MessageSnapshot`：不可变的对话历史。
- `PendingInterrupt`：恢复人工输入或审批所需的数据。

### 3. 执行流程

未来的保存流程：

```text
Mutable AgentState
        ↓ Snapshot Mapper
Immutable AgentTaskSnapshot
        ↓ Checkpoint Store
Persistent Checkpoint
```

未来的恢复流程：

```text
Persistent Checkpoint
        ↓ load
Immutable AgentTaskSnapshot
        ↓ Snapshot Mapper
New Mutable AgentState
        ↓
从 nextStep 继续运行
```

不会尝试恢复 Java 调用栈，只在 Step 边界恢复数据。

### 4. 工程设计与取舍

#### Snapshot 不复用运行时对象

`AgentState`、`AgentStep`、`AgentAction`、`AgentObservation` 和 `ChatMessage`
都是可变对象。如果 Snapshot 直接保存这些引用，Agent 继续运行时可能修改已经保存的
Checkpoint。

因此 Snapshot 使用独立的 record：

```text
运行时模型：为执行方便，可以变化
持久化模型：为恢复可靠，必须不可变
```

构造 Snapshot 时使用 `List.copyOf` 和 `Map.copyOf` 进行防御性复制。

#### 只保存 `nextStep`

原规划同时包含 `currentStep` 和 `nextStep`，两者属于重复状态，可能出现：

```text
currentStep = 3
nextStep = 7
```

本实现只保存语义明确的 `nextStep`。已完成步骤数由 `steps.size()` 推导，并强制：

```text
steps.size() == nextStep
```

步骤索引还必须从零开始连续排列。

#### JSON 字符串作为结构化数据边界

Action 参数和 Observation metadata 可能包含任意嵌套数据。Snapshot 不直接保存
`Map<String, Object>`，而是保存：

```java
String actionArgumentsJson;
String observationMetadataJson;
```

这样不会把 Spring Bean、Client、函数或其他不可序列化对象带入 Checkpoint。

#### 等待状态与中断数据强一致

```text
WAITING_FOR_INPUT    <-> USER_INPUT interrupt
WAITING_FOR_APPROVAL <-> APPROVAL interrupt
```

等待状态没有中断数据时，系统不知道应该向用户展示什么；非等待状态持有
PendingInterrupt，则可能重复处理已经结束的审批。因此构造 Snapshot 时直接拒绝这些
不一致状态。

#### Schema Version 与 Revision 含义不同

- `schemaVersion`：Snapshot 数据结构的版本，用于未来迁移旧数据。
- `revision`：同一个任务的 Checkpoint 修订号，用于乐观锁和并发写保护。

### 5. 测试与验收结果

覆盖内容：

- 顶层 List/Map 的防御性复制
- PendingInterrupt 嵌套 Context 的防御性复制
- 等待状态与中断类型匹配
- 步骤列表与 `nextStep` 一致
- 步骤索引连续
- Schema Version、Revision 和任务身份边界
- `createdAt <= updatedAt`
- JSON 边界字段不能为空

全量验收：

```text
tests=68
failures=0
errors=0
skipped=0
```

### 6. 成熟框架对照

这一设计对应成熟工作流系统中的“Checkpoint 保存数据状态，而不是保存执行线程”。

当前 Snapshot 是框架无关的领域契约。下一层通过 `AgentCheckpointStore` 接口隔离
内存、JDBC 或其他存储实现，Runtime 不直接依赖数据库。

### 7. 面试问题与参考回答

#### 问题一：为什么有 `AgentState` 还要增加 `AgentTaskSnapshot`？

参考回答：

`AgentState` 是面向执行的可变模型，Snapshot 是面向持久化和恢复的不可变模型。直接
持久化运行时对象会导致历史 Checkpoint 被后续执行修改，也容易意外序列化运行时依赖。

#### 问题二：record 就一定是深度不可变的吗？

参考回答：

不一定。record 只保证字段引用不能重新赋值，如果字段是可变 List、Map 或可变对象，
内部内容仍然可以改变。因此构造器必须进行防御性复制，嵌套类型也必须设计为不可变值
对象。

#### 问题三：为什么不同时保存 `currentStep` 和 `nextStep`？

参考回答：

它们表达的是同一个执行游标的两个视角，重复保存会产生一致性问题。只保存恢复真正
需要的 `nextStep`，已完成步骤由列表推导，状态空间更小，也更容易校验。

#### 问题四：`schemaVersion` 和数据库表版本有什么区别？

参考回答：

数据库迁移版本描述存储结构，`schemaVersion` 描述 Snapshot JSON 的数据协议。即使
数据库表没有变化，Snapshot 内部字段变化时仍可能需要升级和兼容旧数据。

#### 问题五：为什么 Snapshot 中的工具参数使用 JSON 字符串？

参考回答：

工具参数是动态结构，使用 `Map<String, Object>` 会允许不可序列化的运行时对象进入
Checkpoint。JSON 字符串明确了持久化边界，同时保留嵌套参数结构。后续由专门的 Mapper
负责序列化和反序列化。

#### 问题六：Checkpoint 为什么不恢复线程或 Java 方法调用栈？

参考回答：

调用栈依赖具体进程和运行时，难以跨重启恢复。Agent 本身以 Step 为离散执行单元，所以
在 Step 边界保存数据，并从 `nextStep` 重新进入循环，恢复模型更简单可靠。

### 8. 对应提交

```text
Add immutable agent task snapshot
```

---

## 2026-07-24：Checkpoint Store 与乐观并发控制

### 1. 本次目标

定义 Runtime 依赖的 Checkpoint 存储端口，并实现线程安全的内存版本，固定首次写入、
顺序更新、并发冲突、生命周期保护和查询语义。

本切片仍未把 Store 接入 `AgentLoopRunner`，也没有实现 JDBC。

### 2. 核心代码

实现文件：

- `agent/checkpoint/AgentCheckpointStore.java`
- `agent/checkpoint/CheckpointConflictException.java`
- `agent/checkpoint/InMemoryAgentCheckpointStore.java`
- `agent/checkpoint/InMemoryAgentCheckpointStoreTest.java`

存储端口：

```java
public interface AgentCheckpointStore {
    long NO_REVISION = -1;

    AgentTaskSnapshot save(
            AgentTaskSnapshot snapshot,
            long expectedRevision
    );

    Optional<AgentTaskSnapshot> load(String taskId);

    List<AgentTaskSnapshot> list(String conversationId);

    void delete(String taskId);
}
```

首次保存协议：

```text
expectedRevision = -1
snapshot.revision = 0
```

更新协议：

```text
数据库当前 revision = N
expectedRevision    = N
新 Snapshot revision = N + 1
```

### 3. 执行流程

正常更新：

```text
load task revision 3
        ↓
生成 revision 4 Snapshot
        ↓ save(expectedRevision = 3)
原子比较当前 revision
        ↓
保存成功
```

并发更新：

```text
恢复请求 A ── load revision 3 ── save revision 4 ── 成功
恢复请求 B ── load revision 3 ── save revision 4 ── revision conflict
```

第二个请求不能覆盖第一个请求已经推进的任务。

### 4. 工程设计与取舍

#### Runtime 依赖端口而不是数据库

`AgentCheckpointStore` 是 Runtime 所需能力的抽象。Runtime 不需要知道底层使用：

- `ConcurrentHashMap`
- JDBC
- Redis
- 其他持久化服务

后续更换存储实现时，不修改 Agent 执行逻辑。

#### Store 不负责生成 revision

调用方显式提交新 revision，并提供 `expectedRevision`：

```java
store.save(nextSnapshot, currentSnapshot.revision());
```

Store 只验证：

```text
当前版本等于 expectedRevision
新版本等于 expectedRevision + 1
```

这样 Snapshot 在进入 Store 前已经完整确定，Store 不会静默修改不可变对象。

#### 使用 `ConcurrentHashMap.compute`

不能使用分离的读写：

```java
if (map.get(taskId).revision() == expectedRevision) {
    map.put(taskId, snapshot);
}
```

因为两个线程可能同时通过 `if`。内存实现使用：

```java
snapshots.compute(taskId, (id, current) -> {
    validateRevision(snapshot, expectedRevision, current);
    return snapshot;
});
```

同一个 key 的读取、校验和写入构成一个原子操作。

#### 任务身份不能在更新中改变

以下字段在同一任务的 revision 之间保持不变：

- `conversationId`
- `userId`
- `originalQuestion`
- `createdAt`

否则一次普通状态更新可能把任务移动到其他用户或对话，形成越权和审计问题。

#### Store 再次保护生命周期迁移

Snapshot 构造器只能验证单个对象内部是否一致，无法知道上一版本状态。Store 同时拥有：

```text
current Snapshot
next Snapshot
```

因此由 Store 校验：

```java
current.status().canTransitionTo(next.status())
```

例如 `COMPLETED -> RUNNING` 会被拒绝。

#### delete 不是正常完成流程

正常完成只写入终态 Snapshot，并保留用于审计。`delete` 只用于管理员清理或数据保留
策略，不由 AgentLoop 自动调用。

### 5. 测试与验收结果

覆盖内容：

- 首次创建 revision 0
- 正常从 revision 0 更新到 revision 1
- 重复创建冲突
- 过期 expectedRevision 冲突
- 跳过 revision
- 修改任务身份
- 从终态恢复为运行态
- 按 conversation 查询并按更新时间倒序
- 显式删除
- 两个线程竞争同一个 revision 时只有一个成功

全量结果：

```text
tests=73
failures=0
errors=0
skipped=0
```

### 6. 成熟框架对照

Checkpoint Store 对应工作流 Runtime 的持久化端口，revision 对应数据库中的
compare-and-set 或乐观锁版本。

内存实现用于快速验证协议和并发语义，不承担跨进程恢复。后续 JDBC 实现必须保持相同
接口和冲突行为，而不是让上层针对不同数据库编写不同恢复逻辑。

### 7. 面试问题与参考回答

#### 问题一：为什么需要乐观锁？

参考回答：

同一个暂停任务可能被两个 HTTP 请求同时恢复。如果没有乐观锁，后写入的请求会覆盖
先写入的状态，导致工具重复执行或审批结果丢失。revision 让 Store 可以检测过期写入。

#### 问题二：为什么不用 Java `synchronized`？

参考回答：

`synchronized` 只能保护当前 JVM。真实部署可能有多个应用实例，最终仍需要数据库条件
更新或其他跨进程 compare-and-set。内存实现使用原子 Map 操作模拟同样的协议。

#### 问题三：为什么检查和写入必须是一个原子操作？

参考回答：

如果先读取 revision、再单独写入，两个线程可能同时看到相同版本并都写入成功。原子
compare-and-set 保证同一个 revision 只能有一个推进者。

#### 问题四：为什么由 Store 校验状态迁移？

参考回答：

单个 Snapshot 只能验证自身字段。合法迁移需要同时查看上一版本和下一版本，而 Store
正好在原子写入期间拥有两者，可以避免校验之后、写入之前状态被其他线程改变。

#### 问题五：内存 Store 已经线程安全，为什么还需要 JDBC Store？

参考回答：

线程安全只解决同一个进程内的并发，不能解决应用重启、多个实例和长期审计。内存实现
主要用于验证端口协议、单元测试和本地演示，跨重启恢复需要持久化实现。

#### 问题六：悲观锁和乐观锁如何选择？

参考回答：

Agent Checkpoint 在一次 Step 完成后才写入，冲突通常较少，而且任务执行可能包含较慢
的模型或工具调用，不适合长期持有数据库锁。因此采用短事务的乐观锁更合适。

### 8. 对应提交

```text
Add optimistic in-memory checkpoint store
```

---

## 2026-07-24：AgentState 与 Snapshot 双向映射

### 1. 本次目标

实现 `AgentTaskSnapshotMapper`，在可变运行时状态和不可变持久化状态之间进行完整转换：

```text
AgentState -> AgentTaskSnapshot -> new AgentState
```

Mapper 只负责确定性数据转换，不负责决定任务状态、revision 或保存时间。

### 2. 核心代码

实现文件：

- `agent/checkpoint/AgentTaskSnapshotMapper.java`
- `agent/checkpoint/AgentTaskSnapshotMappingException.java`
- `agent/checkpoint/AgentTaskSnapshotMapperTest.java`

保存方向：

```java
AgentTaskSnapshot snapshot = mapper.toSnapshot(
        state,
        status,
        revision,
        pendingInterrupt,
        createdAt,
        updatedAt
);
```

恢复方向：

```java
AgentState restoredState = mapper.toState(snapshot);
```

### 3. 执行流程

保存：

```text
AgentState
  ├─ AgentStep -> StepSnapshot
  ├─ ChatMessage -> MessageSnapshot
  ├─ Map arguments -> JSON
  ├─ Map metadata -> JSON
  └─ 恢复字段 -> recoveryContext
               ↓
       AgentTaskSnapshot
```

恢复：

```text
AgentTaskSnapshot
  ├─ StepSnapshot -> new AgentStep
  ├─ MessageSnapshot -> new ChatMessage
  ├─ arguments JSON -> new Map
  ├─ metadata JSON -> new Map
  └─ recoveryContext -> 运行结果和恢复次数
               ↓
          new AgentState
```

Snapshot 中的 `nextStep` 恢复为运行时 `currentStep`。

### 4. 工程设计与取舍

#### Mapper 不推断生命周期

`AgentState` 中没有持久化任务状态，而且 `AgentStopReason` 不能可靠推断
`AgentTaskStatus`。因此 `status` 由编排层显式传入：

```java
mapper.toSnapshot(state, AgentTaskStatus.WAITING_FOR_INPUT, ...);
```

同理，revision 和时间也由调用方提供，保证 Mapper 是可重复测试的纯转换组件。

#### 保存和恢复都创建新对象

保存时不会把 `AgentAction`、`AgentObservation` 或 `ChatMessage` 引用放入 Snapshot。
恢复时也不会把 Snapshot 内部对象直接暴露给 AgentState。

因此：

```text
修改原 AgentState       不影响 Snapshot
修改恢复后的 AgentState 不影响 Snapshot
再次恢复                得到干净的新对象
```

#### 只允许完整 Step 进入 Checkpoint

Checkpoint 保存边界位于 Step 完成之后。以下对象会被拒绝：

```text
step.action == null
step.observation == null
action.type != observation.actionType
```

这样恢复时不会面对“动作已经计划但不知道是否执行”的模糊状态。未来工具执行幂等会使用
独立的执行记录解决崩溃窗口。

#### JSON 损坏在映射边界失败

Snapshot 构造器只验证 JSON 字段非空，Mapper 在恢复时进一步解析为 JSON Object。
损坏数据抛出 `AgentTaskSnapshotMappingException`，不会带着半恢复状态进入 AgentLoop。

#### 第一版 recoveryContext 固定键

当前往返字段：

```text
planningRecoveryAttempts
finalAnswer
stopReason
failureType
errorMessage
```

Mapper 使用集中定义的固定键，避免其他层随意拼写。字段稳定后应升级为类型化
`RecoverySnapshot` 或 `TaskOutcome`。

### 5. 测试与验收结果

覆盖内容：

- 完整运行态往返不丢字段
- Snapshot 与原 AgentState 深度隔离
- 多次恢复得到不同运行时对象
- 不完整 Step 被拒绝
- Action/Observation 类型不一致被拒绝
- 损坏 Step JSON 被拒绝
- 非法 recoveryContext 被拒绝

全量结果：

```text
tests=78
failures=0
errors=0
skipped=0
```

### 6. 成熟框架对照

Mapper 是 Runtime Model 与 Persistence Model 之间的反腐层。它避免持久化结构侵入
AgentLoop，也避免数据库或 JSON 细节泄漏到 Planner、Executor。

后续无论 Store 使用内存还是 JDBC，都只处理 `AgentTaskSnapshot`；AgentLoop 仍只处理
`AgentState`。

### 7. 面试问题与参考回答

#### 问题一：为什么不让 `AgentState` 直接实现序列化？

参考回答：

运行时模型为了执行方便是可变的，并且以后可能加入运行时依赖。独立 Mapper 可以明确
控制持久化字段、深拷贝规则和兼容逻辑，避免持久化协议被运行时代码结构绑死。

#### 问题二：为什么 Mapper 不根据 stopReason 推断 taskStatus？

参考回答：

一次循环停止不等于整个任务结束。例如 `ASK_CLARIFICATION` 应映射为可恢复的等待状态。
生命周期属于编排决策，隐式推断容易把暂停错误地写成终态。

#### 问题三：如何证明快照是真正隔离的？

参考回答：

测试在创建 Snapshot 后修改原 Action 参数、Observation metadata 和历史消息，再验证
恢复结果不变；随后修改恢复对象并再次恢复，验证第二次恢复仍然得到原始数据。

#### 问题四：为什么不保存执行到一半的 Step？

参考回答：

中间状态无法判断工具到底有没有产生副作用，恢复时容易重复执行。第一版只在完整 Step
边界保存，把语义控制为“前面全部完成，从 nextStep 继续”。

#### 问题五：为什么需要专用 MappingException？

参考回答：

它把持久化数据损坏与普通 Agent 执行失败区分开。上层可以针对 Checkpoint 损坏报警、
隔离任务或执行迁移，而不是让它被误判成模型或工具调用失败。

### 8. 对应提交

```text
Add agent task snapshot mapper
```

---

## 后续记录模板

### YYYY-MM-DD：切片名称

#### 1. 本次目标

#### 2. 核心代码

#### 3. 执行流程

#### 4. 工程设计与取舍

#### 5. 测试与验收结果

#### 6. 成熟框架对照

#### 7. 面试问题与参考回答

#### 8. 对应提交
