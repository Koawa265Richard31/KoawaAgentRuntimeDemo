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
