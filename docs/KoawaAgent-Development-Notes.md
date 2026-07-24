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
