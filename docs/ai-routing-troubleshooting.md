# AI 路由测试与 Brotli 响应截断排查

更新日期：2026-10-19；2026-10-23 补充第 8 节（全模型复现、根因闭环与双重修复）。本文记录本仓库实际复现、对照实验和修复结果，重点说明如何从业务测试定位到底层 HTTP 响应读取。

## 1. 测试做了什么

[AiCodeGenTypeRoutingServiceTest](../src/test/java/com/hui/huiaicodemother/ai/AiCodeGenTypeRoutingServiceTest.java) 使用 `@SpringBootTest`，启动完整应用后依次调用三条真实模型需求：

| 用户需求 | 本次实际返回 |
| --- | --- |
| 做一个简单的个人介绍页面 | `html` |
| 做一个公司官网，需要首页、关于我们、联系我们三个页面 | `multi_file` |
| 做一个电商管理系统，包含用户管理、商品管理、订单管理，需要路由和状态管理 | `vue_project` |

这不是隔离单元测试：启动依赖本机配置、MySQL、Redis 和 COS Bean 配置，调用依赖可用的模型地址、密钥及网络。测试当前只有日志，没有分类结果断言；测试通过表示调用未抛异常，表中的分类结果由本次日志确认。

调用链可以拆成：

```text
Spring 启动 → 服务注入 → 提示词加载 → HTTP 请求
→ 响应解压与读取 → 外层 JSON 解析 → 模型回答 → 枚举解析
```

定位时先确认失败发生在哪一步，再检查该步骤的输入和输出。

## 2. 原始现象与关键证据

联网重跑原测试，第一次 `routeCodeGenType(userPrompt)` 调用即失败：

```text
java.lang.RuntimeException:
com.fasterxml.jackson.core.io.JsonEOFException:
Unexpected end-of-input: expected close marker for Object
line: 1, column: 2
```

堆栈经过 `SyncRequestExecutor.execute`、`Json.fromJson`，表示模型接口的外层 HTTP 响应解析失败，尚未进入 `CodeGenTypeEnum` 转换。

只启用响应日志后看到：

```text
HTTP response:
- status code: 200
- body: {
```

HTTP 200 仅说明请求获得成功状态，不能证明正文已经完整读取。此时应调查响应正文的传输、解压和读取，不能仅凭 JSON 异常认定提示词或枚举有问题。

排查最初还遇到沙箱的 `Permission denied: getsockopt`；这是复现环境阻止联网，不能作为 IDEA 原始失败的结论。允许联网后才取得上述真实异常。

## 3. 对照实验：每次只改变一个条件

先通过模拟模型返回 `HTML`、`MULTI_FILE`、`VUE_PROJECT`，确认提示词资源、代理创建和合法枚举解析均可工作。随后使用相同模型地址、密钥、系统提示词和第一条需求，对比客户端及读取方式：

| 实验 | 结果 | 缩小的范围 |
| --- | --- | --- |
| 原路由测试，默认 HTTP 客户端 | 稳定出现外层 JSON 解析错误 | 模型 HTTP 调用之后 |
| 独立请求，默认 Apache 客户端与 Spring 字符串转换 | HTTP 200，正文长度 1，只有 `{` | 传输或读取过程 |
| 同请求改用 JDK HTTP 客户端 | 收到完整 JSON，回答 `HTML` | Apache 与 JDK 的处理差异 |
| Apache 请求指定 `Accept-Encoding: identity` | 收到完整 JSON | 压缩处理 |
| Apache 禁用内容压缩 | 收到完整 JSON | 压缩处理 |
| Apache 保持默认压缩，改用缓冲区循环读取正文 | 收到完整 JSON | 解压与读取方式的组合 |
| 检查 Apache 解压前的响应头 | `Content-Encoding: br` | Brotli 解压路径 |
| Apache 请求指定 `Accept-Encoding: gzip` | 收到完整 JSON | gzip 可规避该故障 |

独立请求用于隔离 HTTP 行为，不替代原测试；最后仍需回到完整 Spring 测试确认配置实际生效。

## 4. 根因：间接依赖改变了响应解压路径

依赖来源为：

```text
WebDriverManager 6.1.0
├── Apache HttpClient 5（本项目解析为 5.5）
└── org.brotli:dec:0.1.2
```

LangChain4j Spring Boot starter 使用 Spring RestClient；本项目依赖中存在 Apache HttpClient 后，它成为底层客户端，Brotli 解码库的存在使客户端支持 `br` 响应。本次已从解压前的实际响应头确认服务端返回 Brotli 压缩内容。

检查本地 `BrotliInputStream` 0.1.2 字节码可见：

1. 单字节 `read()` 会预先解码并缓存一批内容。
2. Spring 为判断响应是否为空，先读一个字节，再将该字节放回。
3. 后续批量读取时，解码器可能先把缓存中的剩余内容复制到目标数组。
4. 如果此时没有新解码输出，该版本仍直接返回 `-1`，没有把已复制的字节计入返回值。
5. 上层按“流已结束”处理，最终只保留放回的第一个字符 `{`。

因此，这次问题发生于 Brotli 解码和 Spring 读取流程的组合。单独使用缓冲区循环读取能够成功，与原测试失败并不矛盾。

上游当前 [BrotliInputStream 源码](https://github.com/google/brotli/blob/master/java/org/brotli/dec/BrotliInputStream.java) 已按“缓存复制字节 + 本轮输出字节”判断返回值；当前仓库实际使用的是旧的 0.1.2，不能用上游主分支的实现替代已安装依赖的检查。

## 5. 当前修复与适用边界

在 [application.yml](../src/main/resources/application.yml) 的普通聊天模型下指定 gzip：

```yaml
langchain4j:
  open-ai:
    chat-model:
      custom-headers:
        Accept-Encoding: gzip
```

这项配置保留压缩能力，并让普通聊天模型避开有问题的 Brotli 解码路径。它是当前依赖组合的兼容处理，没有替换或修补 Brotli 库，也不影响路由提示词和枚举定义。

配置作用于自动配置的普通 `ChatModel`。它不表示手工创建的 Vue 流式模型或所有其他 HTTP 客户端均已修改或完成兼容验证。后续升级依赖时，需要重新检查实际依赖版本与响应读取行为，再决定是否删除此配置。

## 6. 复现与验证

准备 Java 21、Maven Wrapper 所需依赖、本机 MySQL/Redis、`application-local.yml` 的模型和 COS 配置；执行测试会向所配置的外部模型发送路由提示词和三条需求。

Windows PowerShell 示例：

```powershell
.\mvnw.cmd `
  '-Dtest=AiCodeGenTypeRoutingServiceTest#testRouteCodeGenType' `
  '-Dlangchain4j.open-ai.chat-model.log-requests=false' `
  '-Dlangchain4j.open-ai.chat-model.log-responses=false' `
  test
```

需要排查正文时，只把 `log-responses` 临时设为 `true`，保留请求日志关闭，避免请求头中的密钥进入诊断输出。检查 `target/surefire-reports` 中该测试的文本报告，以及控制台的三条分类日志。

2026-10-19 修复后原测试结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

三条实际返回见本文第一节。这次验证没有运行应用创建、源码下载、浏览器截图或 COS 上传的端到端用例。

## 7. 可以复用的排查顺序

1. 取得原始异常和完整堆栈，分清复现环境错误与业务错误。
2. 把调用拆成阶段，先定位失败阶段，记录已经成功的步骤。
3. 检查失败阶段真正收到的内容；HTTP 状态、JSON 正文和模型回答分别判断。
4. 用模拟返回值验证本地处理，用同输入的对照实验验证外部交互。
5. 每次只改变一个条件：客户端、压缩设置或读取方式，避免同时改多个配置。
6. 顺着依赖树检查实际运行的实现，尤其关注新增依赖带来的自动配置变化。
7. 做最小修改，重跑原测试，分别记录已通过的场景和未验证的业务边界。

## 8. 2026-10-23 全模型复现、根因闭环与双重修复

第 5 节的 gzip 配置当时仅覆盖自动装配的普通聊天模型；应用内手工创建的路由模型与两个流式模型仍在默认压缩协商下运行，随后在 `/api/app/add`（AI 智能路由）链路稳定复现同样的 `JsonEOFException`。

### 8.1 真实密钥 A/B 复现

使用本机 `application-local.yml` 中的真实密钥，绕开 Spring 直接构造与生产一致的 `OpenAiChatModel` 做对照：

| 分组 | 请求头 | 结果 |
| --- | --- | --- |
| A | 默认（`gzip, x-gzip, deflate` + classpath 存在 Brotli 解码器时协商 `br`） | 稳定复现正文只剩 `{` |
| B | 强制 `Accept-Encoding: gzip` | 完整 JSON |
| C | 强制 `Accept-Encoding: identity` | 完整 JSON |

结论与第 4 节一致且闭环：问题只取决于"Brotli 解码路径是否被触发"，与密钥、提示词、接口和网络路径无关（同机 curl、本地模拟服务、JDK 客户端对照均完整）。注意一个排障陷阱：某次验证 classpath 恰好在排除 Brotli 的窗口期生成，导致"默认头也正常"的假象；验证依赖变更类修复时必须重新生成 classpath 再下结论。

### 8.2 双重修复

1. 根治：`pom.xml` 的 `webdrivermanager` 依赖排除 `org.brotli:dec`，classpath 不再存在 0.1.2 解码器，客户端不会协商 `br`。
2. 保险层：三个自建模型配置类（routing / streaming / reasoning）新增 `customHeaders` 字段并在 Builder 透传，`application.yml` 四类模型统一配置 `custom-headers.Accept-Encoding: gzip`；即使依赖组合再次变化，也不会回到 Brotli 路径。

### 8.3 修复后验证

重启后端后：`POST /api/app/add` 返回 `code:0`（真实路由约 1.2 秒）；浏览器端到端验证"登录 → 创建应用 → AI 路由多文件模式 → 流式生成 → 预览渲染"全链路通过，未再出现截断。

返回：[项目文档索引](README.md)。
