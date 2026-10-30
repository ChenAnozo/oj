# 提交状态枚举重构方案

## 1. 问题背景

当前 `QuestionSubmitStatusEnum` 只有 4 个值：

| 值 | 枚举常量 | 含义 |
|----|---------|------|
| 0 | WAITING | 等待中 |
| 1 | RUNNING | 判题中 |
| 2 | SUCCEED | 成功 |
| 3 | FAILED | 失败 |

其中 `FAILED(3)` 同时覆盖了两类性质完全不同的失败，无法区分：

| 类别 | 责任方 | 用户是否需要重试？ | 示例场景 |
|------|--------|------------------|---------|
| **用户代码问题** | 用户 | 否，需修改代码后重新提交 | 编译失败、时间超限、内存超限、答案错误、运行错误、输出超限、危险操作、展示错误 |
| **系统/服务故障** | 开发者（服务自身） | 是，用户可原样重试 | 代码沙箱服务宕机、HTTP 调用超时、Docker 执行超时/异常、RabbitMQ 消息丢失、数据库写入失败、未预期的内部异常 |

---

## 2. 现状分析

### 2.1 当前状态流转

```
用户提交代码
  │ status = WAITING (0)
  ▼
JudgeServiceImpl.doJudge() 消费消息
  │ status = RUNNING (1)
  ▼
调用代码沙箱 (RemoteCodeSandbox / ExampleCodeSandbox)
  │
  ├── [成功] 沙箱返回 ExecuteCodeResponse
  │     │
  │     ├── outputList != null  → status = SUCCEED (2)
  │     │                          judgeInfo.message = 判题结果（ACCEPTED / WRONG_ANSWER / TLE ...）
  │     │
  │     └── outputList == null  → status = FAILED (3)   ← 用户代码问题
  │                                judgeInfo.message = 判题结果
  │
  └── [异常] try-catch 捕获异常  → status = FAILED (3)   ← 系统/服务问题
                                    judgeInfo.message = "系统繁忙，请稍后重试"
```

**问题：** 两个分支最终都设 `status = FAILED(3)`，前端和数据库无法区分。

### 2.2 相关枚举

#### JudgeInfoMessageEnum（判题结论消息）

该枚举已经涵盖所有用户级的判题结论，字段 `value` 为中文展示文案：

| 枚举常量 | value（中文） |
|----------|-------------|
| ACCEPTED | Accepted |
| WRONG_ANSWER | Wrong Answer |
| COMPILE_ERROR | Compile Error |
| MEMORY_LIMIT_EXCEEDED | 内存溢出 |
| TIME_LIMIT_EXCEEDED | Time Limit Exceeded |
| PRESENTATION_ERROR | 展示错误 |
| OUTPUT_LIMIT_EXCEEDED | 输出溢出 |
| DANGEROUS_OPERATION | 危险操作 |
| RUNTIME_ERROR | 运行错误 |
| SYSTEM_ERROR | 系统错误 |
| WAITING | 等待中 |

> **注意：** `JudgeInfoMessageEnum` 描述的是**判题结论**（用户代码对/错/超时等），而 `QuestionSubmitStatusEnum.status` 描述的是**判题流水线是否正常完成**。两者职责不同，但当前在错误场景下存在重叠和混淆。

### 2.3 前端现状

#### ViewQuestionView.vue（做题页面 - 判题结果弹窗）

```ts
// 前端自定义的状态枚举（值与后端不一致）
const JudgeStatusEnum = {
  WAITING: -1,  // 前端自己的初始态
  JUDGING: 0,   // 对应后端 RUNNING(1)
  SUCCESS: 2,   // 对应后端 SUCCEED(2)
  FAILED: 3     // 对应后端 FAILED(3)
};
```

弹窗渲染逻辑（`ViewQuestionView.vue:49-53`）：
```html
<div v-if="judgeStatus === 2">{{ judgeMessage }}</div>
<div v-if="judgeStatus === 3">Compile Error</div>
```

- status=2 时显示 `judgeInfo.message`（后端已返回中文文案）
- status=3 时**硬编码**显示 "Compile Error"，未使用 `judgeInfo.message`

#### QuestionSubmitView.vue（提交列表页）

- `status` 列：直接显示原始数字（0/1/2/3），无文案映射
- `judgeInfo` 列：直接 `JSON.stringify()` 输出整个 JudgeInfo 对象（含 memory/time/message）

### 2.4 既有 Bug（附带发现）

| 文件 | 行号 | 问题 |
|------|------|------|
| `CodeSandboxTemplate.java` | 121 | `setStatus(3)` 硬编码数字，未用枚举 |
| `CodeSandboxTemplate.java` | 133 | `setStatus(1)` 硬编码，且 1 是 RUNNING 而非 SUCCEED——语义错误 |
| `CodeSandboxTemplate.java` | 159 | `getErrorResponse()` 设 `setStatus(2)`，2 是 SUCCEED，但在错误场景错误地返回了成功状态 |
| `JavaNativeCodeSandboxOld.java` | 100,111,152 | 同上三类硬编码问题 |

---

## 3. 改造方案

### 3.1 核心思路

在 `QuestionSubmitStatusEnum` 中新增 `SYSTEM_ERROR`，将系统级故障与用户代码失败分离：

```
WAITING (0)  → 等待中
RUNNING (1)  → 判题中
SUCCEED (2)  → 判题流水线正常完成（具体结果看 judgeInfo.message）
FAILED (3)   → 判题流水线正常完成，但用户代码有问题（编译失败、沙箱内异常）
SYSTEM_ERROR (4) → 判题流水线异常中断，服务自身故障
```

#### 语义定义

| status | 含义 | judgeInfo.message | 用户行为 |
|--------|------|-------------------|---------|
| WAITING | 已提交，等待消费 | 等待中 | 等待 |
| RUNNING | 正在沙箱执行 | 判题中 | 等待 |
| SUCCEED | 沙箱执行成功，判题完成 | Accepted / Wrong Answer / 时间超限 等 | 查看结果 |
| FAILED | 沙箱返回执行异常（编译失败、运行错误） | Compile Error / Runtime Error 等 | 修改代码重交 |
| SYSTEM_ERROR | 沙箱服务不可达/超时/崩溃等 | 系统繁忙，请稍后重试 | 原样重试 |

### 3.2 改动清单

#### 3.2.1 后端：QuestionSubmitStatusEnum.java

**文件：** `yuoj-backend-microservice/yuoj-backend-model/src/main/java/com/yupi/yuojbackendmodel/enums/QuestionSubmitStatusEnum.java`

新增枚举值：

```java
WAITING("等待中", 0),
RUNNING("判题中", 1),
SUCCEED("成功", 2),
FAILED("失败", 3),
SYSTEM_ERROR("系统错误", 4);
```

#### 3.2.2 后端：JudgeServiceImpl.java

**文件：** `yuoj-backend-microservice/yuoj-backend-judge-service/src/main/java/com/yupi/yuojbackendjudgeservice/judge/JudgeServiceImpl.java`

**改动点 1**（第 95 行）—— catch 块改为 SYSTEM_ERROR：

```java
// 原代码
failUpdate.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());

// 改为
failUpdate.setStatus(QuestionSubmitStatusEnum.SYSTEM_ERROR.getValue());
```

> 理由：此处是 `codeSandbox.executeCode()` 抛异常，属于沙箱服务不可达/超时等系统故障。

**改动点 2**（第 115 行）—— outputList 为 null 时保持 FAILED，不变。

> 理由：沙箱服务正常返回了响应，但 outputList 为空说明用户代码在沙箱内执行出错。

#### 3.2.3 后端：CodeSandboxTemplate.java

**文件：** `yuoj-code-sandbox/src/main/java/com/yupi/yuojcodesandbox/CodeSandboxTemplate.java`

需要在此文件中新增对 `QuestionSubmitStatusEnum` 的依赖（目前 `code-sandbox` 模块不依赖 `yuoj-backend-model`，需调整 Maven 依赖或在 code-sandbox 模块的 `ExecuteCodeResponse` 注释中说明）。

**改动点 1**（第 121 行）—— 错误消息时设 FAILED：

```java
// 原代码
executeCodeResponse.setStatus(3);

// 改为（若模块间有依赖）
executeCodeResponse.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
// 或维持硬编码但添加注释：// 对应 QuestionSubmitStatusEnum.FAILED
```

**改动点 2**（第 133 行）—— 全部用例执行完成时设 SUCCEED：

```java
// 原代码（语义错误，1 是 RUNNING）
executeCodeResponse.setStatus(1);

// 改为
executeCodeResponse.setStatus(QuestionSubmitStatusEnum.SUCCEED.getValue());
// 或改为 2 并加注释
```

**改动点 3**（第 159 行）—— getErrorResponse() 方法：

```java
// 原代码（语义错误，错误场景设了 SUCCEED）
executeCodeResponse.setStatus(2);

// 改为
executeCodeResponse.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
// 或改为 3 并加注释
```

> **重要：** 若 `code-sandbox` 模块不便于依赖 `yuoj-backend-model`，可改为在 `ExecuteCodeResponse` 的注释中标注数字含义，并在 `JudgeServiceImpl` 中做好状态映射。

#### 3.2.4 后端：JavaNativeCodeSandboxOld.java

**文件：** `yuoj-code-sandbox/src/main/java/com/yupi/yuojcodesandbox/JavaNativeCodeSandboxOld.java`

与 `CodeSandboxTemplate.java` 相同的三处硬编码问题（第 100、111、152 行），统一修正。

#### 3.2.5 后端：ExecuteCodeResponse.java（两处副本）

**文件：**
- `yuoj-code-sandbox/src/main/java/com/yupi/yuojcodesandbox/model/ExecuteCodeResponse.java`
- `yuoj-backend-microservice/yuoj-backend-model/src/main/java/com/yupi/yuojbackendmodel/codesandbox/ExecuteCodeResponse.java`

更新 `status` 字段注释：

```java
/**
 * 状态码，对应 QuestionSubmitStatusEnum：
 * 0=WAITING  1=RUNNING  2=SUCCEED  3=FAILED  4=SYSTEM_ERROR
 */
private Integer status;
```

#### 3.2.6 前端：ViewQuestionView.vue

**文件：** `yuoj-frontend/src/views/question/ViewQuestionView.vue`

**改动点 1**（第 85-90 行）—— 新增 SYSTEM_ERROR 状态：

```ts
const JudgeStatusEnum = {
  WAITING: -1,
  JUDGING: 0,
  SUCCESS: 2,
  FAILED: 3,
  SYSTEM_ERROR: 4   // 新增
};
```

**改动点 2**（第 49-53 行）—— 弹窗渲染逻辑改为使用 `judgeMessage`：

```html
<!-- 原代码 -->
<div v-if="judgeStatus === 2">{{ judgeMessage }}</div>
<div v-if="judgeStatus === 3">Compile Error</div>

<!-- 改为 -->
<div v-if="judgeStatus === JudgeStatusEnum.SUCCESS">{{ judgeMessage }}</div>
<div v-if="judgeStatus === JudgeStatusEnum.FAILED">{{ judgeMessage }}</div>
<div v-if="judgeStatus === JudgeStatusEnum.SYSTEM_ERROR">{{ judgeMessage }}</div>
```

> 理由：
> 1. 将硬编码数字改为枚举引用，可读性更好
> 2. status=3 不再硬编码 "Compile Error"，改为使用 `judgeInfo.message`，后端会返回具体错误类型
> 3. 新增 SYSTEM_ERROR 展示分支

**改动点 3**（第 114-156 行）—— 轮询终止条件：

```ts
// 原代码
if (res.data.status === 2 || res.data.status === 3) { ... }

// 改为
if (res.data.status === JudgeStatusEnum.SUCCESS ||
    res.data.status === JudgeStatusEnum.FAILED ||
    res.data.status === JudgeStatusEnum.SYSTEM_ERROR) { ... }
```

#### 3.2.7 前端：QuestionSubmitView.vue

**文件：** `yuoj-frontend/src/views/question/QuestionSubmitView.vue`

**可选改动** —— 将 `status` 列的原始数字映射为中文标签：

```ts
const statusLabelMap: Record<number, string> = {
  0: '等待中',
  1: '判题中',
  2: '成功',
  3: '失败',
  4: '系统错误'
};
```

---

## 4. 兼容性说明

### 4.1 数据库

- `question_submit.status` 字段为 `INT` 类型
- 历史数据中 status=3 的记录无需修改，仍然是"失败"（当时不存在系统错误的区分）
- 新增值 4 只需改代码，无需数据迁移

### 4.2 API 接口

- `status` 字段类型为 `Integer`，新增值 4 对旧客户端兼容（旧客户端不认识 4，但不会报错）
- 建议前端做兜底：未知 status 值时显示 "未知状态"

### 4.3 前端的 JudgeStatusEnum 值不一致问题

前端自定义的 `JudgeStatusEnum` 值与后端不完全一致（前端 WAITING=-1、JUDGING=0，后端 WAITING=0、RUNNING=1），但这只在 `ViewQuestionView.vue` 组件内部使用，不影响数据库和 API，本次不修改。

---

## 5. 风险与注意事项

1. **`code-sandbox` 模块的依赖问题：** 若要在 `CodeSandboxTemplate` 中引用 `QuestionSubmitStatusEnum`，需要让 `code-sandbox` 模块依赖 `yuoj-backend-model`（或引入一个共享的枚举模块）。替代方案：在 code-sandbox 中新建一个本地常量类，避免跨模块依赖。

2. **`getErrorResponse()` 方法未被使用：** `CodeSandboxTemplate.getErrorResponse()` 和 `JavaNativeCodeSandboxOld.getErrorResponse()` 目前都是 private 方法，且没有被 `executeCode()` 调用（编译/运行异常会直接往上抛 RuntimeException）。考虑后续是否应该改为在 `executeCode()` 中 catch 异常并调用此方法兜底，而不是让异常裸奔到上层。

3. **前端对 JUDGING 状态的处理：** 前端 `JUDGING=0` 对应后端 `RUNNING=1`，但前端只在提交后立即设 JUDGING，然后通过轮询拿后端真实 status。实际轮询时后端返回的是 1(RUNNING)，但前端 `JudgeStatusEnum` 里没有 1 的映射。建议后续统一。

4. **`JavaNativeCodeSandboxOld` 是否还在使用？** 如果该文件已废弃，可不修改，仅修改 `CodeSandboxTemplate`。

---

## 6. 改动文件汇总

| 序号 | 文件 | 改动类型 |
|------|------|---------|
| 1 | `yuoj-backend-microservice/yuoj-backend-model/src/main/java/com/yupi/yuojbackendmodel/enums/QuestionSubmitStatusEnum.java` | 新增 SYSTEM_ERROR(4) |
| 2 | `yuoj-backend-microservice/yuoj-backend-judge-service/src/main/java/com/yupi/yuojbackendjudgeservice/judge/JudgeServiceImpl.java` | catch 块改用 SYSTEM_ERROR |
| 3 | `yuoj-code-sandbox/src/main/java/com/yupi/yuojcodesandbox/CodeSandboxTemplate.java` | 硬编码数字替换为枚举引用；修正 getErrorResponse |
| 4 | `yuoj-code-sandbox/src/main/java/com/yupi/yuojcodesandbox/JavaNativeCodeSandboxOld.java` | 同 Template 的修复（若仍在使用） |
| 5 | `yuoj-code-sandbox/src/main/java/com/yupi/yuojcodesandbox/model/ExecuteCodeResponse.java` | 更新 status 字段注释 |
| 6 | `yuoj-backend-microservice/yuoj-backend-model/src/main/java/com/yupi/yuojbackendmodel/codesandbox/ExecuteCodeResponse.java` | 更新 status 字段注释 |
| 7 | `yuoj-frontend/src/views/question/ViewQuestionView.vue` | 新增 SYSTEM_ERROR 分支；status=3 改用 judgeMessage |
| 8 | `yuoj-frontend/src/views/question/QuestionSubmitView.vue` | （可选）status 列中文映射 |
