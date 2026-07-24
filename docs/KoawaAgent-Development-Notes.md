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

## 2026-07-24：Checkpoint 用例编排服务

### 1. 本次目标

通过 `AgentCheckpointService` 把 Mapper 和 Store 组合成可直接调用的应用用例：

- 创建任务的初始 Checkpoint。
- 保存任务的下一 revision。
- 加载 Snapshot 并重建新的 AgentState。

本切片的 `load` 只读取数据，不自动恢复执行或改变任务状态。

### 2. 核心代码

实现文件：

- `agent/checkpoint/AgentCheckpointService.java`
- `agent/checkpoint/CheckpointNotFoundException.java`
- `agent/checkpoint/AgentCheckpointServiceTest.java`

创建：

```java
AgentTaskSnapshot initial = checkpointService.create(initialState);
```

更新：

```java
AgentTaskSnapshot next = checkpointService.save(
        state,
        AgentTaskStatus.WAITING_FOR_INPUT,
        pendingInterrupt
);
```

加载：

```java
Optional<LoadedAgentCheckpoint> loaded =
        checkpointService.load(taskId);
```

### 3. 执行流程

初始创建：

```text
initial AgentState
  currentStep = 0
  steps = []
        ↓ Mapper
RUNNING Snapshot revision 0
        ↓ Store save(expectedRevision = -1)
初始 Checkpoint
```

版本化保存：

```text
load current Snapshot revision N
        ↓
Mapper 创建 revision N + 1
  createdAt 保持不变
  updatedAt 使用当前时间
        ↓
Store save(expectedRevision = N)
```

加载：

```text
Store load
    ↓
Snapshot Mapper
    ↓
new AgentState
```

### 4. 工程设计与取舍

#### Service、Mapper、Store 各自职责

```text
Service：编排用例和 revision 流程
Mapper：运行时对象与持久化对象转换
Store：原子保存和并发冲突检测
```

Service 不直接操作 Map，也不处理 JSON；Store 不负责构造 AgentState。

#### 创建必须发生在 Step 0 之前

初始 Checkpoint 强制：

```text
currentStep = 0
steps = []
```

如果已经执行过步骤，应使用 `save`，不能伪装成新任务写 revision 0。

#### createdAt 与 updatedAt 分离

- `createdAt` 在任务所有 revision 中保持不变。
- `updatedAt` 表示当前 revision 的保存时间。

Service 在更新时从旧 Snapshot 继承 `createdAt`，并通过注入的 `Clock` 获取新
`updatedAt`。

#### load 不等于 resume

`load` 可以读取 RUNNING、等待态或终态，用于审计和展示，但不会：

- 把等待态改成 RUNNING。
- 获取执行权。
- 启动 AgentLoop。
- 重新计算执行 deadline。

真正的 resume/claim 需要输入校验、状态迁移和执行权控制，将在 HITL 阶段单独实现。

#### 缺失与冲突使用不同异常

```text
CheckpointNotFoundException：任务不存在
CheckpointConflictException：任务存在，但 revision 已变化
```

上层 API 可以分别映射为 404 和 409，而不是统一返回内部错误。

### 5. 测试与验收结果

覆盖内容：

- 创建 revision 0 RUNNING Snapshot
- 初始创建时间
- 保存下一 revision
- createdAt 保持、updatedAt 更新
- PendingInterrupt 保存
- 加载后重建独立 AgentState
- 修改加载结果不污染 Store
- 缺失任务
- 重复创建
- 拒绝用已执行状态创建初始 Checkpoint

全量结果：

```text
tests=82
failures=0
errors=0
skipped=0
```

### 6. 成熟框架对照

Checkpoint Service 属于应用层用例，不是领域对象，也不是存储适配器。它让上层 Runtime
只调用“创建、保存、加载”语义，而不用了解 revision 0、`NO_REVISION` 或时间继承等
细节。

### 7. 面试问题与参考回答

#### 问题一：为什么不能让 AgentLoop 直接调用 Store？

参考回答：

Store 只提供持久化能力，不知道初始 revision、时间继承和 Mapper 转换。应用服务集中
编排这些规则，避免 AgentLoop 同时承担执行、序列化和存储职责。

#### 问题二：为什么注入 Clock？

参考回答：

直接调用 `Instant.now()` 会让时间相关测试不稳定。注入 Clock 后可以固定或推进时间，
精确验证 createdAt 和 updatedAt。

#### 问题三：为什么 load 不直接恢复执行？

参考回答：

读取数据和获取执行权是两个不同操作。直接执行会让查询接口意外推进任务，也无法处理
等待输入、终态保护和并发 resume。load 保持无副作用，resume 由独立用例实现。

#### 问题四：为什么区分 NotFound 和 Conflict？

参考回答：

NotFound 表示资源不存在，Conflict 表示调用方基于过期版本操作。两者的客户端处理方式
不同：前者通常停止请求，后者可能重新加载最新状态后决定是否重试。

#### 问题五：为什么 Service 仍然不能完全防止工具重复执行？

参考回答：

Checkpoint revision 能防止两个结果同时覆盖状态，但两个执行者可能在写 Checkpoint 前
都已经调用工具。解决副作用重复需要执行租约和 `ToolExecutionStore` 幂等键，这是后续
独立能力。

### 8. 对应提交

```text
Add checkpoint application service
```

---

## 2026-07-24：Snapshot JSON 持久化协议

### 1. 本次目标

在真正连接数据库前，为 `AgentTaskSnapshot` 建立稳定、版本感知的 JSON 编解码边界。

当前 Snapshot 仍然没有写入数据库；本切片解决 JDBC Store 保存 JSON 前的协议问题。

### 2. 核心代码

实现文件：

- `agent/checkpoint/AgentTaskSnapshotJsonCodec.java`
- `agent/checkpoint/AgentTaskSnapshotCodecException.java`
- `agent/checkpoint/AgentTaskSnapshotJsonCodecTest.java`

编码：

```java
String snapshotJson = codec.encode(snapshot);
```

解码：

```java
AgentTaskSnapshot snapshot = codec.decode(snapshotJson);
```

Codec 显式注册：

```java
new JavaTimeModule()
```

并关闭时间戳数组格式：

```java
SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
```

因此 `Instant` 保存为可读且精度稳定的 ISO-8601 字符串。

### 3. 执行流程

未来 JDBC 写入：

```text
AgentTaskSnapshot
       ↓ encode
Snapshot JSON
       ↓ INSERT / UPDATE
checkpoint.snapshot_json
```

未来 JDBC 读取：

```text
checkpoint.snapshot_json
       ↓ decode
AgentTaskSnapshot
       ↓ Snapshot Mapper
AgentState
```

### 4. 工程设计与取舍

#### revision 与 schemaVersion

`revision` 表示同一个任务的状态版本：

```text
revision 0：任务创建
revision 1：完成一个 Step
revision 2：进入等待态
```

它用于乐观锁：

```text
UPDATE ... WHERE task_id = ? AND revision = expectedRevision
```

`schemaVersion` 表示 Snapshot JSON 的结构版本：

```text
schemaVersion 1：当前字段结构
schemaVersion 2：未来的新字段或结构
```

区别：

```text
revision      每个任务独立递增，解决并发覆盖
schemaVersion 全局数据协议版本，解决新旧代码兼容
```

#### 读写两端都检查版本

编码未知版本可能把当前代码不理解的数据写入数据库；解码未知版本可能产生字段缺失或错误
默认值。因此 Codec 在 encode 和 decode 两端都要求：

```java
snapshot.schemaVersion()
        == AgentTaskSnapshot.CURRENT_SCHEMA_VERSION
```

未来支持 V2 时，应先增加显式 Migrator，而不是直接取消校验。

#### Codec 与 Mapper 不合并

```text
Mapper：AgentState <-> AgentTaskSnapshot
Codec：AgentTaskSnapshot <-> JSON
Store：JSON <-> Database
```

分层后，可以独立测试运行态映射、JSON 协议和数据库并发，不需要通过一条超长链路定位
错误。

#### 专用 CodecException

空 JSON、损坏 JSON、JSON null、字段构造失败和未知版本统一转化为
`AgentTaskSnapshotCodecException`。上层可以把它识别为 Checkpoint 数据问题，而不是
模型或工具执行失败。

### 5. 测试与验收结果

完整 Snapshot 往返覆盖：

- 微秒级 `Instant`
- StepSnapshot
- Action arguments JSON
- Observation metadata JSON
- MessageSnapshot
- recoveryContext
- PendingInterrupt
- revision 和 schemaVersion

异常覆盖：

- 损坏 JSON
- 空字符串
- JSON `null`
- 编码未知 schemaVersion
- 解码未知 schemaVersion

全量结果：

```text
tests=86
failures=0
errors=0
skipped=0
```

### 6. 成熟框架对照

持久化系统通常不会直接依赖 Java 对象的默认序列化，而是建立带版本的数据协议。这样
应用升级时可以识别旧 Snapshot，并通过迁移逻辑恢复，而不是依赖 Java 类结构碰巧兼容。

### 7. 面试问题与参考回答

#### 问题一：revision 是什么？

参考回答：

revision 是单个任务 Checkpoint 的单调递增版本号，用于乐观锁。更新时只有数据库当前
revision 等于调用方读取到的 expectedRevision 才能成功。

#### 问题二：revision 与 schemaVersion 有什么区别？

参考回答：

revision 解决同一个任务的并发更新，几乎每次保存都会变化；schemaVersion 解决 Snapshot
JSON 格式兼容，只有数据协议变化时才变化。

#### 问题三：为什么落库前要单独测试 JSON Codec？

参考回答：

数据库保存成功不代表数据能够恢复。Instant、record、嵌套枚举和不可变集合都可能在
反序列化时失败，Codec 往返测试可以在接入数据库前隔离这些问题。

#### 问题四：遇到未知 schemaVersion 为什么不忽略？

参考回答：

静默忽略可能把缺失字段当成默认值并继续执行工具，风险高于明确失败。应先识别版本，
执行经过测试的数据迁移，再交给当前 Runtime。

#### 问题五：为什么使用 ISO-8601 保存 Instant？

参考回答：

它带有明确 UTC 语义，可读性好，并能保留亚秒精度。相比时间戳数组或本地时间字符串，
跨语言和跨时区恢复更稳定。

### 8. 对应提交

```text
Add versioned checkpoint JSON codec
```

---

## 2026-07-24：JDBC Checkpoint 持久化

### 1. 本次目标

将 Snapshot 从进程内 Map 扩展到真实关系数据库，提供 PostgreSQL 运行配置、Flyway
迁移、JDBC Store，以及基于 H2 的真实 SQL 集成测试。

当前 `AgentCheckpointService` 已默认装配 JDBC Store，但 AgentLoop 尚未调用该 Service。

### 2. 核心代码

实现文件：

- `agent/checkpoint/JdbcAgentCheckpointStore.java`
- `agent/checkpoint/CheckpointWriteValidator.java`
- `agent/checkpoint/CorruptedCheckpointException.java`
- `agent/checkpoint/AgentCheckpointConfiguration.java`
- `db/migration/V1__create_agent_checkpoint.sql`
- `agent/checkpoint/JdbcAgentCheckpointStoreTest.java`
- `compose.yaml`

表结构：

```sql
CREATE TABLE agent_checkpoint (
    task_id VARCHAR(128) PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128),
    revision BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    schema_version INTEGER NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

### 3. 执行流程

首次创建：

```text
Snapshot revision 0
       ↓ Codec.encode
Snapshot JSON
       ↓ INSERT
agent_checkpoint
```

更新：

```sql
UPDATE agent_checkpoint
SET revision = ?,
    status = ?,
    snapshot_json = ?,
    updated_at = ?
WHERE task_id = ?
  AND revision = ?;
```

```text
updatedRows = 1：保存成功
updatedRows = 0：revision 冲突
```

读取：

```text
SELECT row
   ↓ decode snapshot_json
AgentTaskSnapshot
   ↓ 对照索引列
一致：返回 Snapshot
不一致：CorruptedCheckpointException
```

### 4. 工程设计与取舍

#### 索引列与 JSON 分工

普通列保存：

- taskId
- conversationId
- userId
- revision
- status
- schemaVersion
- createdAt
- updatedAt

这些字段用于索引、排序、过滤和 CAS 更新。

`snapshot_json` 保存恢复所需的完整状态，避免第一版把 Step、消息和中断过度拆表。

#### INSERT 与 UPDATE 分开

新任务要求：

```text
expectedRevision = -1
revision = 0
```

使用 INSERT。主键重复被转换为 `CheckpointConflictException`。

已有任务使用带 revision 条件的 UPDATE，不采用“先判断再普通 UPDATE”。

#### 共享写入校验

原本校验只存在于 InMemory Store。现在提取为 `CheckpointWriteValidator`，让内存和 JDBC
实现共享：

- revision 顺序
- 任务身份不变
- 生命周期迁移
- schemaVersion 不倒退
- maxSteps 不变化
- 已完成 Step 历史不能重写

这样两种 Store 不会出现不同业务语义。

#### 为什么 UPDATE 前仍然 SELECT

SELECT 用于验证：

- 当前任务身份
- 状态迁移
- Step 历史前缀
- 当前实际 revision

SELECT 与 UPDATE 之间仍可能发生并发，因此 UPDATE 必须继续带 revision 条件。前置 SELECT
负责领域校验，条件 UPDATE 负责最终原子性。

#### 数据库列与 JSON 交叉校验

读取时验证：

```text
row.task_id        == snapshot.taskId
row.revision       == snapshot.revision
row.status         == snapshot.status
row.schema_version == snapshot.schemaVersion
row.created_at     == snapshot.createdAt
row.updated_at     == snapshot.updatedAt
```

不一致通常表示手工修改、旧 Writer、迁移错误或数据损坏，不能当作普通 revision 冲突重试。

#### 时间精度

PostgreSQL `TIMESTAMP WITH TIME ZONE` 通常保存微秒精度，而 Java Instant 可能有纳秒精度。
JDBC 索引列写入和对照时统一截断到微秒；完整精度仍保留在 Snapshot JSON 中。

### 5. 测试与验收结果

真实 H2 SQL 测试覆盖：

- 执行 V1 建表脚本
- INSERT revision 0
- snapshot_json 实际存在
- 读取并完整 decode
- revision 条件更新
- 重复创建冲突
- 过期 revision 冲突
- 两线程竞争只有一个成功
- conversation 查询与排序
- DELETE
- 人工篡改 status 列后检测损坏
- 纳秒 Instant 与数据库微秒精度兼容

Spring 上下文测试还验证：

- Hikari DataSource 启动
- Flyway 发现 V1 migration
- migration 成功应用
- JDBC Store 和 Service Bean 成功创建

全量结果：

```text
tests=90
failures=0
errors=0
skipped=0
```

### 6. 成熟框架对照

这种设计属于“索引字段 + JSON 状态”的混合持久化：

- 高频查询和并发字段保持关系型列。
- 工作流状态主体使用带 schemaVersion 的 JSON。
- Runtime 依赖 Store 端口，不依赖数据库技术。

第一版避免为动态 Agent Step 建立大量关联表，同时仍保留数据库级查询和并发能力。

### 7. 面试问题与参考回答

#### 问题一：为什么有 snapshot_json 还要保存 revision 列？

参考回答：

revision 需要出现在 UPDATE WHERE 条件和索引中。每次解析 JSON 再比较无法形成高效、清晰
的数据库 CAS 操作。

#### 问题二：SELECT 后为什么 UPDATE 还要检查 revision？

参考回答：

SELECT 和 UPDATE 之间存在并发窗口。SELECT 用于领域校验，最终 UPDATE 的 revision 条件
才负责数据库原子性。

#### 问题三：影响行数为 0 代表什么？

参考回答：

可能是任务不存在，也可能是 revision 已被其他写入推进。Store 再查询实际 revision，
生成包含 expected/actual 的冲突异常。

#### 问题四：为什么数据库列与 JSON 要重复保存？

参考回答：

列用于高效查询和并发，JSON 用于完整恢复。重复字段读取时必须交叉校验，避免两份数据
静默分叉。

#### 问题五：为什么使用 Flyway？

参考回答：

Checkpoint 结构属于持久化协议的一部分。Flyway 让建表和后续变更具有顺序、校验和部署
记录，避免依赖应用启动时临时执行不可追踪的 DDL。

#### 问题六：H2 测试通过是否等于 PostgreSQL 一定通过？

参考回答：

不等于。H2 用于快速验证 SQL 和 Store 语义，仍需要 PostgreSQL 集成或 Testcontainers
测试覆盖驱动、时区、锁和方言差异。这是后续 CI 增强项。

### 8. 对应提交

```text
Add JDBC checkpoint persistence
```

---

## 2026-07-24：Checkpoint 接入 Agent Runtime

### 1. 本次目标

把已经实现的 Snapshot、Service 和 JDBC Store 接入真实执行链路，形成三个明确的持久化
边界：

- 新任务进入 AgentLoop 前创建 revision 0。
- 每个完整 Step 提交后保存 RUNNING revision。
- Runner 返回后保存终态或等待输入状态。

本切片不实现 Resume。相同 taskId 再次调用当前的新任务入口会因初始 Checkpoint 已存在而
冲突，这是刻意保留的保护；恢复必须走后续独立的 Resume 用例。

### 2. 核心代码

实现文件：

- `agent/runner/AgentCheckpointLifecycle.java`
- `agent/runner/AgentCheckpointLifecycleException.java`
- `agent/checkpoint/PersistentAgentCheckpointLifecycle.java`
- `agent/runner/AgentLoopRunner.java`
- `agent/service/impl/DefaultAgentChatService.java`
- `agent/config/AgentConfiguration.java`
- `agent/checkpoint/AgentCheckpointConfiguration.java`

Runtime 只依赖生命周期端口：

```java
public interface AgentCheckpointLifecycle {
    void initialize(AgentState state);
    void stepCommitted(AgentState state);
    void completed(AgentState state);
}
```

持久化实现再调用 `AgentCheckpointService`，因此 Runner 不知道 Snapshot、JSON、JDBC 或
revision 如何实现。

### 3. 执行流程

一次正常执行的 revision 序列：

```text
DefaultAgentChatService
  └─ initialize(initialState)
       └─ revision 0 / RUNNING / nextStep 0

AgentLoopRunner
  └─ 执行 Action，得到 Observation
  └─ steps.add(step)
  └─ currentStep = stepIndex + 1
  └─ stepCommitted(state)
       └─ revision 1 / RUNNING / nextStep 1

DefaultAgentChatService
  └─ completed(completedState)
       └─ revision 2 / COMPLETED / nextStep 1
```

这里 Step revision 和终态 revision 分开保存。前者表示“这一步已经完整提交”，后者表示
“本轮运行已经结束”。即使最终 Action 本身产生答案，也不能在 Step 进入历史前先保存
COMPLETED。

停止原因映射：

```text
FINAL_ANSWER       → COMPLETED
ASK_CLARIFICATION  → WAITING_FOR_INPUT + USER_INPUT interrupt
CANCELLED          → CANCELLED
TIMEOUT            → TIMED_OUT
ERROR              → FAILED
MAX_STEPS          → FAILED
```

### 4. 工程设计与取舍

#### 为什么增加 Lifecycle 端口

如果 Runner 直接调用 `AgentCheckpointService`，执行循环会依赖 Snapshot 映射和持久化
用例。Lifecycle 端口只描述执行时机，让 Runtime 控制“何时保存”，让 Checkpoint 模块控制
“保存什么、如何保存”。

`NOOP` 实现保留了原有构造器和纯单元测试的兼容性；Spring 正式装配时注入
`PersistentAgentCheckpointLifecycle`。

#### 保存动作具体在哪里

Step 保存位于：

```java
state.getSteps().add(step);
state.setCurrentStep(stepIndex + 1);
checkpointLifecycle.stepCommitted(state);
```

所以真正的提交点不是 `return`。`return` 只把内存状态交还调用方；Lifecycle 调用才会经
Mapper、Codec 和 Store 把状态落库。

初始与终态保存由 `DefaultAgentChatService` 包围 `runner.run(...)`，因为它负责一次完整
Run 的应用层边界，而 Runner 只负责循环内的 Step 边界。

#### 为什么保存失败不转换成 Agent ERROR

规划或工具异常属于 Agent 执行结果，可以写成 `FAILED`。Checkpoint 保存异常代表系统
无法确认刚刚的状态是否持久化。如果 Runner 把它吞掉并改成普通 ERROR：

- 可能继续执行并重复产生外部副作用。
- 可能再写一次终态，掩盖最初的 revision 冲突。
- 调用方会误以为任务已可靠结束。

因此持久化异常被包装为 `AgentCheckpointLifecycleException` 并直接向上传播，交给 API
错误处理和监控；它不会被 AgentLoop 的通用异常分支改写。

#### ASK_CLARIFICATION 为什么创建 interrupt

`WAITING_FOR_INPUT` 仅表示生命周期状态，`PendingInterrupt` 才保存恢复所需的等待原因、
提示语、类型和创建时间。后续 Resume API 会验证并消费这个 interrupt，而不是仅根据
状态字符串猜测上下文。

### 5. 测试与验收结果

新增测试覆盖：

- revision 依次为 0、1、2。
- Step 回调发生在 Step 和 nextStep 都提交之后。
- 终止原因到任务状态的完整映射。
- ASK_CLARIFICATION 生成 USER_INPUT interrupt。
- Checkpoint 异常向上传播，不被改写为 Agent ERROR。
- Chat Service 严格按 initialize、run、completed 的顺序调用。

全量结果：

```text
tests=96
failures=0
errors=0
skipped=0
```

### 6. 成熟框架对照

这与成熟图工作流框架在节点边界提交 Checkpoint 的思想一致：恢复依赖已提交边界，而不是
恢复 Java 调用栈。KoawaAgent 当前以一个完整 `AgentStep` 作为最小提交单元，并用独立端口
保持 Runtime 与具体 Checkpointer 解耦。

当前仍缺少恢复侧闭环：加载 Snapshot、校验任务可恢复、重建 AgentState，并从 nextStep
继续。这将是下一切片的核心。

### 7. 面试问题与参考回答

#### 问题一：为什么不在每次方法 return 时统一保存？

参考回答：

不同 return 只表示控制流结束，不等于状态已形成一致的持久化边界。Step 必须在 Action、
Observation、steps 和 nextStep 全部更新后保存；整个 Run 的终态则由应用服务在 Runner
返回后保存。

#### 问题二：为什么最终一步会产生两个 revision？

参考回答：

第一个 revision 提交完整 Step，第二个 revision 提交任务生命周期终态。这让“已执行到
哪里”和“为什么停止”分别具有明确边界，也为故障诊断和恢复提供更准确的信息。

#### 问题三：Checkpoint 保存失败后为什么不继续运行？

参考回答：

系统已经无法证明当前 Step 是否持久化。继续执行可能导致恢复时重复调用外部工具，违反
at-least-once 场景下的副作用安全，因此采用 fail-fast。

#### 问题四：Lifecycle 接口是否过度设计？

参考回答：

它隔离的是变化轴：Runner 的循环算法与 JDBC、Codec、Snapshot 映射的变化原因不同。
接口只有三个稳定边界，并提供 NOOP，不要求业务代码理解存储实现，因此成本较低。

#### 问题五：有了自动保存，为什么还不能 Resume？

参考回答：

保存和恢复是两个不同用例。恢复还需要判断状态是否允许继续、重建 deadline、处理等待中断、
保证只有一个执行者取得运行权，并从 nextStep 开始而不是重新创建 revision 0。

### 8. 对应提交

```text
Integrate checkpoints with agent runtime
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
