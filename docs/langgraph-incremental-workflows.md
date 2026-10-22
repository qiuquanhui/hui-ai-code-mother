# LangGraph 质检、并发、子图与流式接口

更新日期：2026-10-22。参考项目：`E:\myself\portfolio\code\yupi\yu-ai-code-mother`。这是在首次五节点同步基础上的增量实现；首次迁移、图片 Key 配置和历史 48 项测试记录见 [原同步记录](langgraph-sync-verification.md)。

## 按功能阅读与提交

本轮按以下六个功能依赖顺序提交，每个提交包含对应实现、测试和功能文档：

1. [图片计划与内部并行收集](langgraph-image-planning.md)
2. [代码质检与有限修复](langgraph-code-quality.md)
3. [显式并发图片分支](langgraph-concurrent-images.md)
4. [图片收集子图](langgraph-image-subgraphs.md)
5. [工作流 HTTP 与流式事件](langgraph-workflow-streaming.md)
6. [Studio 可视化调试](langgraph-studio-debugging.md)

## 本轮差异与同步范围

重新比较了两边的 Maven 依赖、工作流 Java 源码、提示词、控制器、静态演示页和测试。参考项目的 `langgraph4j` 包从 21 个 Java 文件增加至 37 个，本项目已补齐相应文件，并新增工作流控制器、两个提示词和两个同源演示页。

| 能力 | 实现入口 | 当前行为 |
| --- | --- | --- |
| 普通工作流 | [CodeGenWorkflow](../src/main/java/com/hui/huiaicodemother/langgraph4j/CodeGenWorkflow.java) | 图片计划与节点内部并行收集 → 提示词增强 → 路由 → 生成 → 质检；Vue 通过质检后构建 |
| 显式并发分支 | [CodeGenConcurrentWorkflow](../src/main/java/com/hui/huiaicodemother/langgraph4j/CodeGenConcurrentWorkflow.java) | 图片计划后并发执行内容图、插画、架构图、Logo 四个分支，再汇总并执行生成、质检、构建 |
| 并发子图 | [CodeGenSubgraphWorkflow](../src/main/java/com/hui/huiaicodemother/langgraph4j/CodeGenSubgraphWorkflow.java) | 四类收集任务封装为独立子图，共享父图状态，再汇总结果 |
| 结构化 AI 服务 | [图片计划](../src/main/java/com/hui/huiaicodemother/langgraph4j/ai/ImageCollectionPlanService.java)、[代码质检](../src/main/java/com/hui/huiaicodemother/langgraph4j/ai/CodeQualityCheckService.java) | 复用现有普通 ChatModel，解析图片任务列表和 `isValid/errors/suggestions` |
| 独立工作流 HTTP 入口 | [WorkflowSseController](../src/main/java/com/hui/huiaicodemother/controller/WorkflowSseController.java) | 同步、Flux SSE、SseEmitter 三种方式，仅管理员可执行 |
| Studio 调试 | [LangGraphStudioSampleConfig](../src/main/java/com/hui/huiaicodemother/langgraph4j/config/LangGraphStudioSampleConfig.java) | 默认关闭；显式启用时提供图调试和 `originalPrompt` 输入 |

依赖与参考项目保持一致：`langgraph4j-core`、`langgraph4j-studio-springboot` 均为 `1.6.0-rc2`，DashScope SDK 仍为 `2.21.1`。继续排除 DashScope 带入的 `slf4j-simple`，保留 Spring Boot 的 Logback。

## 同步时修复的行为

- 质检不再把服务异常当成通过。无效或缺少 `isValid` 的结果会进入修复流程，质检服务调用异常直接失败。
- 最多生成三次，包含第一次生成；达到上限明确失败。可通过 `langgraph.workflow.max-generation-attempts` 调整为 1～10。
- 修复提示保留原始需求和素材，附加质检错误与建议；应用 ID、生成目录和模型记忆继续按本项目的规则隔离。
- 读取质检源码时剪枝依赖、构建产物、隐藏目录，不跟随符号链接；最多读取 200 个代码文件、合计 256 KiB，超过限制明确失败，避免无限请求。空项目不会因为拼接了标题而被当成有源码。
- HTML / 多文件质检通过后直接返回源码目录；Vue 只有构建成功且存在 `dist/index.html` 才返回成功。
- 并发分支分别更新独立状态键，再由聚合节点合并，不同时覆盖同一个可变上下文。每次执行使用并关闭自己的线程池。
- 图片计划失败可降级为无素材；单个图片任务失败保留其他成功结果。
- HTTP Flux 返回结构化 SSE，避免把已经格式化的 SSE 文本再次包装为 `data:`。失败只发送 `workflow_error`，不发送 `workflow_completed`，错误内容不回显底层模型地址或 Key。
- Flux 取消、SseEmitter 超时或连接结束会释放订阅并中断工作线程；第三方模型或云端已接受的任务是否立即停止，仍取决于供应商行为。
- 保留现有文件工具、可视化编辑和原生保存失败传播等修复，没有用参考项目的旧实现覆盖。

## 配置与运行前提

**真实图片功能需要单独配置凭据，程序启动和自动化测试通过不能替代线上联调。**

| 配置 | 用途 | 缺少时的结果 |
| --- | --- | --- |
| 原有 LangChain4j 模型地址和 Key、MySQL/Redis | 图片计划、路由、代码生成、代码质检和记忆 | 图片计划可降级；生成或质检不可用时工作流失败 |
| `PEXELS_API_KEY` | Pexels 内容图片搜索 | 对应工具返回空列表，不发起真实搜图请求 |
| `DASHSCOPE_API_KEY` | Logo 生成 | 对应工具返回空列表，不发起真实生成请求 |
| `DASHSCOPE_IMAGE_MODEL` | 可选，默认 `wan2.2-t2i-flash` | 使用默认模型；账户仍需有对应模型调用能力 |
| Mermaid CLI 与 `cos.client` | 架构图转换和上传 | 失败时该工具返回空列表 |
| `langgraph.workflow.max-generation-attempts` | 质检不通过时最多生成次数 | 默认 3，合法范围 1～10 |
| `langgraph.studio.enabled` | Studio 调试开关 | 默认 `false`，不注册 Studio `/init`、`/stream` Servlet |

Key 放在 IDEA 启动环境变量或 Git 忽略的 `application-local.yml`，更改后重启后端。完整示例见 [图片配置说明](langgraph-sync-verification.md#启用功能前的配置)。本轮没有新增独立的图片计划或质检 Key，它们复用现有 ChatModel。

Studio 仅用于本地调试。启用时将后端绑定到本机，例如使用启动参数 `--langgraph.studio.enabled=true --server.address=127.0.0.1`；输入字段为 `originalPrompt`。Studio 由依赖包提供的 Servlet 执行，不走下面演示控制器的管理员校验，因此默认关闭。

## 调用与接口

三种工作流都注册为 Spring Service。注入 `CodeGenWorkflow` 默认得到普通工作流；按具体类型注入 `CodeGenConcurrentWorkflow` 或 `CodeGenSubgraphWorkflow` 可使用另外两种图。都支持 `executeWorkflow(prompt, appId)` 和继承的流式执行方法。

未传 ID 的独立调用会分配唯一 ID，但不会创建应用数据库记录，不自动持久化业务对话历史，也不自动部署。显式传入 ID 的内部调用应由调用方先完成业务权限检查。

| 方法 | 路径（含当前 `/api` 上下文） | 参数 | 响应 |
| --- | --- | --- | --- |
| POST | `/api/workflow/execute` | `prompt` | 最终 `WorkflowContext` |
| GET | `/api/workflow/execute-flux` | `prompt` | `text/event-stream` |
| GET | `/api/workflow/execute-sse` | `prompt` | `text/event-stream` |

三个接口均校验管理员登录态，使用普通工作流；它们没有替换现有 `/app` 聊天生成接口。SSE 按顺序提供 `workflow_start`、若干 `step_completed`，最后是 `workflow_completed` 或 `workflow_error`。进度中的 `node` 标识实际节点；这里不提供模型逐 Token 事件。

管理员登录后，可在同一个后端源打开 `/api/test-flux-workflow.html` 或 `/api/test-sseemitter-workflow.html`。演示页使用同源 URL 与登录 Cookie，不硬编码主机端口。

## 按功能提交的独立验证

六个功能按依赖顺序拆分为独立提交，每个提交包含对应功能文档和测试。验证从 Git 暂存树导出快照，只使用该提交及之前的源码，不依赖工作区里尚未提交的功能。

| 顺序 | 功能 | 快照累计通过用例 | 验证命令 |
| --- | --- | --- | --- |
| 1 | 图片计划与节点内部并行收集 | 51 | Maven `test` |
| 2 | 代码质检与有限修复重试 | 63 | Maven `test` |
| 3 | 独立状态合并的并发图片工作流 | 70 | Maven `test` |
| 4 | 四类图片收集子图 | 77 | Maven `test` |
| 5 | 管理员同步与可取消 SSE 接口 | 90 | Maven `test` |
| 6 | 可选 Studio 与功能文档索引 | 92 | Maven `verify`，含打包 |

表中的用例数是每个提交快照的累计数量，不能相加为新增用例总数；各阶段失败、错误和跳过均为 0。各功能的边界和配置分别保存在上方六份功能文档中。独立验证日志位于 Git 忽略的 `tmp/langgraph-feature-commits/validation-1.log` 至 `validation-6.log`，最终快照报告位于相应 `snapshot-6-*` 的 `target/surefire-reports`。模型和真实云服务的验证边界与下文相同。

## 自动化验证范围

2026-10-22 最终 Maven `verify` 结果为 `BUILD SUCCESS`：19 个测试类共 **92 项全部通过，失败 0、错误 0、跳过 0**；Spring Boot 上下文启动和可执行 JAR 打包均通过。

| 测试组 | 用例数 |
| --- | --- |
| 普通图 `CodeGenWorkflowTest` | 20 |
| 并发图 / 子图 | 7 + 7 |
| Flux 生命周期 / 控制器实际 MVC SSE | 6 + 7 |
| Studio 配置 / 质检文件读取 | 2 + 4 |
| 图片服务装配 / 新 AI 服务结构化 JSON 解析 | 2 + 3 |
| 图片请求工具 / 生成图片工具 | 6 + 4 |
| 既有文件工具、SSE 错误、构建器、解析器、流处理、保存和启动测试 | 24 |

本轮验证执行真实编译图、节点路由、并发屏障、源码解析落盘、质检源码读取、LangChain4j JSON 解析以及 Spring MVC 的实际 SSE 编码和异步生命周期。模型与外部图片服务使用 Mock／本地 HTTP，Vue/npm、Mermaid 转换和 COS 上传使用替代实现。

重点场景包括三种图、三种代码生成类型、质检拒绝后修复、重试上限、无效质检响应、四分支同时执行、分支失败后保留其他结果、重复执行不串图、Flux 取消、SSE 超时、管理员权限、错误不泄露底层详情，以及 Studio 默认关闭/显式启用。

真实模型、Pexels、unDraw、DashScope、Mermaid CLI 和 COS 未完成本轮线上联调；本轮不以 Mock 的返回值宣称这些外部功能已经可用。静态演示页已适配接口，但未执行浏览器端人工验收。

复验使用 Java 21、Maven 3.9.11 和项目内临时目录。核心命令如下，运行前将 `JAVA_HOME` 指向本机 Java 21：

```powershell
New-Item -ItemType Directory -Force tmp/langgraph-tests | Out-Null
.\mvnw.cmd '-DargLine=-Djava.io.tmpdir=E:/myself/portfolio/code/hui-ai-code-mother/tmp/langgraph-tests' '-Dtest=HuiAiCodeMotherApplicationTests,CodeParserTest,CodeGenWorkflowTest,CodeGenConcurrentWorkflowTest,CodeGenSubgraphWorkflowTest,WorkflowStreamingTest,WorkflowSseControllerTest,LangGraphStudioConfigTest,CodeQualityCheckNodeTest,ImageCollectionPlanServiceTest,CodeQualityCheckServiceTest,ImageToolsTest,GeneratedImageToolsTest,ImageCollectionServiceTest,ProjectFileToolsTest,AppControllerStreamErrorTest,VueProjectBuilderTest,JsonMessageStreamHandlerTest,VisualEditPersistenceTest' verify
```

首次增量验证日志保留在 `tmp/langgraph-latest-verify.log`；按功能拆分后的最终验证日志为 `tmp/langgraph-feature-commits/validation-6.log`，最新报告与可执行 JAR 在对应 `snapshot-6-*` 的 `target/` 下。测试快照、日志、报告和构建产物均被 Git 忽略。
