# 代码沙箱容错修复方案

## 一、问题总览

| # | 文件 | 行号 | 问题 | 后果 |
|---|---|---|---|---|
| 1 | `yuoj-backend-judge-service/.../JudgeServiceImpl.java` | 85 | `executeCode()` 无 try-catch | 沙箱异常 → 提交卡 RUNNING |
| 2 | `yuoj-backend-judge-service/.../RemoteCodeSandbox.java` | 39 | `HttpUtil.createPost()` 未设超时 | 沙箱假死 → 线程永久阻塞 |
| 3 | `yuoj-code-sandbox/.../JavaDockerCodeSandbox.java` | 181 | `awaitCompletion(5000L, MICROSECONDS)` = 5ms | Docker 容器刚启动就被杀 |
| 3 | `yuoj-code-sandbox/.../CppDockerCodeSandbox.java` | 164 | 同上 | 同上 |

---

## 二、修复 1：`JudgeServiceImpl.java` 加 try-catch + 兜底更新

### 文件路径

```
yuoj-backend-judge-service/src/main/java/com/yupi/yuojbackendjudgeservice/judge/JudgeServiceImpl.java
```

### 改造前（第 85 行）

```java
ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);
```

异常直接向上抛，后面第 88-128 行的判题结果处理和状态更新全部跳过。

### 改造后

```java
ExecuteCodeResponse executeCodeResponse;
try {
    executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);
} catch (Exception e) {
    log.error("代码沙箱调用失败，questionSubmitId={}", questionSubmitId, e);
    // 兜底：将提交状态标记为失败
    QuestionSubmit failUpdate = new QuestionSubmit();
    failUpdate.setId(questionSubmitId);
    failUpdate.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
    JudgeInfo judgeInfo = new JudgeInfo();
    judgeInfo.setMessage("系统繁忙，请稍后重试");
    failUpdate.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
    questionFeignClient.updateQuestionSubmitById(failUpdate);
    return null;
}
```

### 说明

沙箱任何异常（连接拒绝、超时、Docker 错误）都被捕获，兜底把提交状态从 RUNNING 改为 FAILED，用户至少能看到判题结束，不再无限转圈。返回值 `null` 表示本次判题未产出有效结果，由消息消费者 nack 处理（现有逻辑不变）。

---

## 三、修复 2：`RemoteCodeSandbox.java` 加 HTTP 超时

### 文件路径

```
yuoj-backend-judge-service/src/main/java/com/yupi/yuojbackendjudgeservice/judge/codesandbox/impl/RemoteCodeSandbox.java
```

### 改造前（第 39 行）

```java
String response = HttpUtil.createPost(url).header(AUTH_REQUEST_HEADER, AUTH_REQUEST_SECRET).body(json).execute().body();
```

无超时设置，默认永不超时。

### 改造后

```java
String response = HttpUtil.createPost(url)
        .header(AUTH_REQUEST_HEADER, AUTH_REQUEST_SECRET)
        .body(json)
        .setConnectionTimeout(2000)
        .timeout(30000)
        .execute()
        .body();
```

### 说明

Hutool 的 `HttpUtil.createPost()` 默认不设超时，沙箱服务网络不通时线程会阻塞到系统 TCP 超时（分钟级）。加连接超时 2 秒（连不上快速返回）和读超时 30 秒（代码执行最坏情况），超时后抛 `SocketTimeoutException`，由修复 1 的 try-catch 兜底处理。

---

## 四、修复 3：修复 Docker 执行超时单位 Bug

### 文件路径（需改 2 个文件）

```
yuoj-code-sandbox/src/main/java/com/yupi/yuojcodesandbox/JavaDockerCodeSandbox.java
yuoj-code-sandbox/src/main/java/com/yupi/yuojcodesandbox/CppDockerCodeSandbox.java
```

### 改造前

`JavaDockerCodeSandbox.java` 第 181 行：

```java
dockerClient.execStartCmd(execId).exec(execStartResultCallback).awaitCompletion(5000L, TimeUnit.MICROSECONDS);
```

`CppDockerCodeSandbox.java` 第 164 行，同上。

### 改造后（两个文件都改）

```java
dockerClient.execStartCmd(execId).exec(execStartResultCallback).awaitCompletion(10L, TimeUnit.SECONDS);
```

### 说明

`TimeUnit.MICROSECONDS` 表示微秒，5000 微秒 = **5 毫秒**。Docker 容器创建 + `java Main` 进程启动至少需要数百毫秒，5 毫秒后就被强制终止，代码根本没执行。改为 `10L, TimeUnit.SECONDS`（10 秒），覆盖正常的代码执行时间。这个 Bug 会导致 Docker 模式下判题结果永远是空的。

---

## 五、改动速查表

| 文件 | 改了什么 | 行号 |
|---|---|---|
| `JudgeServiceImpl.java` | `executeCode()` 外包 try-catch，catch 里兜底更新 FAILED | ~85 |
| `RemoteCodeSandbox.java` | `createPost()` 链式调用加 `.setConnectionTimeout(2000)` 和 `.timeout(30000)` | 39 |
| `JavaDockerCodeSandbox.java` | `awaitCompletion(5000L, MICROSECONDS)` → `awaitCompletion(10L, SECONDS)` | 181 |
| `CppDockerCodeSandbox.java` | 同上 | 164 |

---

## 六、验证方案

| 场景 | 操作 | 预期 |
|---|---|---|
| 沙箱宕机 | 停掉 `8090` 后提交代码 | 2 秒内连接超时 → 状态变为 FAILED → 显示"系统繁忙" |
| 沙箱繁忙 | 提交死循环代码 | 30 秒读超时 → 状态变为 FAILED |
| Docker 正常判题 | 提交正确 Java 代码 | 10 秒内正常返回结果，不再因 5ms 超时而失败 |
