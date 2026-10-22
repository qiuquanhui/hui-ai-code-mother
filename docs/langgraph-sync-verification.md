# LangGraph4j 同步与验证

同步日期：2026-10-21。参考项目：`E:\myself\portfolio\code\yupi\yu-ai-code-mother`。

文档更新：2026-10-22。启用真实图片功能前，请先完成下面的配置；自动化测试通过不等于云服务已经可用。

本文保留首次五节点同步和 48 项测试的历史记录。同日参考项目新增的质检重试、并发/子图、独立 SSE 接口及 Studio 已另行同步，当前实现与 92 项测试范围见 [本轮增量记录](langgraph-incremental-workflows.md)。下文的迁移范围和测试计数以首次迁移为背景；现有 `/app` 聊天接口仍未切换到新工作流。

## 启用功能前的配置

| 功能 | Spring 配置 | 环境变量或运行环境 | 配置要求与降级行为 |
| --- | --- | --- | --- |
| Pexels 内容图片搜索 | `pexels.api-key` | `PEXELS_API_KEY` | 实际搜图必需；缺少 Key 时不发送请求，返回空列表 |
| DashScope Logo 图片生成 | `dashscope.api-key` | `DASHSCOPE_API_KEY` | 实际生成必需；缺少 Key 时不发送请求，返回空列表 |
| DashScope 图片模型 | `dashscope.image-model` | `DASHSCOPE_IMAGE_MODEL` | 可选，默认 `wan2.2-t2i-flash`；需要账户具备对应模型调用能力 |
| Mermaid 架构图 | 无新增密钥配置 | 安装 Mermaid CLI，并使后端 PATH 能找到 `mmdc` / `mmdc.cmd` | 转换成功后上传 COS；CLI 或上传失败返回空列表 |
| COS 上传 | `cos.client.host`、`secretId`、`secretKey`、`region`、`bucket` | 现有本机配置；`host` 带 `https://` | 真实架构图上传必需；字段齐全或 Bean 创建成功不代表真实上传已通过 |
| 图片收集 AI、类型路由和代码生成 | 原有 `langchain4j.open-ai.chat-model` / `streaming-chat-model` | 原有模型地址、Key、MySQL/Redis 等基础配置 | 图片 Key 不能替代模型 Key；图片收集失败可降级，代码生成或构建失败会终止工作流 |

在 IDEA 的后端启动配置 **Run/Debug Configurations → Environment variables** 中分别添加两个 Key 并重启后端：

```text
PEXELS_API_KEY=你的PexelsKey
DASHSCOPE_API_KEY=你的DashScopeKey
```

命令行启动时，在启动后端的同一个 PowerShell 会话中设置：

```powershell
$env:PEXELS_API_KEY = '你的PexelsKey'
$env:DASHSCOPE_API_KEY = '你的DashScopeKey'
# 按需覆盖；未设置时使用默认值。
$env:DASHSCOPE_IMAGE_MODEL = 'wan2.2-t2i-flash'
.\mvnw.cmd spring-boot:run
```

也可写入被 Git 忽略的 `src/main/resources/application-local.yml`：

```yaml
pexels:
  api-key: "你的PexelsKey"
dashscope:
  api-key: "你的DashScopeKey"
  image-model: wan2.2-t2i-flash
```

以上均为占位值。真实密钥只放在环境变量或本机忽略配置中，不提交到 Git。`unDraw` 工具不需要新增 Key，但仍依赖外部接口；参考项目的地址模板保留，并可通过 `undraw.search-url` 覆盖，本轮未验证真实接口的当前可用性。

## 差异与同步范围

对比了两个项目的 Maven 依赖、后端 Java 源码、提示词和测试，并在比较时归一化包名、项目名及换行。参考项目新增的主要后端能力是 `langgraph4j` 下的 21 个 Java 文件、`SpringContextUtil` 和图片收集提示词。

同步了完整工作流、五个执行节点、状态与图片模型、图片收集 AI 服务、四种图片工具和演示程序，包名适配为 `com.hui.huiaicodemother`。依赖版本与参考项目一致：

| 依赖 | 版本 |
| --- | --- |
| `org.bsc.langgraph4j:langgraph4j-core` | `1.6.0-rc2` |
| `com.alibaba:dashscope-sdk-java` | `2.21.1` |

公共业务文件中存在注释、排版和已有实现差异。保留 hui 项目的路径约束、文件编辑校验、流式错误传播、Vue 构建并发控制和可视化编辑修复；参考项目放在 `service` 包的 `ProjectDownloadServiceImpl` 在本项目已有 `service.impl` 实现，因此没有重复添加 Spring Bean。

## 迁移时修复的问题

- 图片收集节点原来重复调用同一个 AI 服务，改为每次只调用一次。
- 代码生成原来固定使用 `appId=0`，改为从上下文取得 ID；支持显式传入业务应用 ID，无 ID 的独立调用分配唯一 ID。
- Vue 构建失败原来仍返回源目录，改为抛出异常；同时检查生成的 `index.html` 和构建后的 `dist/index.html`。
- 修正图片提示词中不存在的 `generateArchitectureDiagram`，与实际工具 `generateMermaidDiagram` 对齐。
- Pexels、DashScope 凭据提供空默认值；没有凭据时对应工具返回空列表，图片服务失败时工作流继续使用原提示词。
- 插画搜索对关键词执行参数编码，避免空格、`&` 等字符改变请求含义。支持通过 `undraw.search-url` 覆盖参考项目中的接口模板。
- Mermaid CLI 使用独立参数、执行超时和退出码校验，上传失败时也清理临时 SVG。
- 排除 DashScope SDK 间接引入的 `slf4j-simple`，避免与 Spring Boot 的 Logback 冲突导致应用无法启动。
- 原有可视化编辑 E2E 测试服务限定为 `visual-e2e` profile，避免普通 Spring Boot 测试扫描时出现重复 Bean。其独立启动入口会自动启用该 profile。
- 修复原有 `CodeParserTest` 缺失 Markdown 代码围栏的示例输入，补齐内容断言；纯解析测试不再启动 Spring 上下文。

## 调用方式

`CodeGenWorkflow` 已注册为 Spring Service，可以注入后调用：

```java
WorkflowContext result = codeGenWorkflow.executeWorkflow("创建一个技术博客网站", appId);
String outputDirectory = result.getBuildResultDir();
```

按参考项目的实现边界，本次增加独立工作流服务和 `WorkflowApp` 启动入口；现有聊天 SSE 接口仍使用原来的生成流程。独立调用 `executeWorkflow(prompt)` 会分配唯一 ID，但不会创建数据库中的应用记录；业务调用应传入已创建应用的 ID。

Pexels 和 DashScope Key 对启动是可选的，对实际使用对应图片功能是必需的。不能把空列表降级、启动成功或 Mock 测试通过视为真实搜图、Logo 生成和云上传已经可用。

## 验证方式

2026-10-21 首次验证及 2026-10-22 提交前复验均执行了 Maven `verify`，结果为 `BUILD SUCCESS`：48 项测试全部通过，失败 0、错误 0、跳过 0；Spring Boot 上下文启动和可执行 JAR 打包均通过。首次本机日志保存在 `tmp/langgraph-verify.log`，提交前复验日志保存在 `tmp/langgraph-commit-verify.log`，最新测试的 XML / 文本报告保存在 `target/surefire-reports`。这些日志和构建产物被 Git 忽略，不随源码提交。

其中四个 LangGraph 测试类合计 24 项，全部通过且无跳过：

| 测试类 | 用例数 | 真实执行与替代范围 |
| --- | --- | --- |
| `ImageCollectionServiceTest` | 2 | Spring Bean 装配、日志绑定与提示词资源；模型为 Mock |
| `GeneratedImageToolsTest` | 4 | Logo SDK 参数/结果、架构图上传后清理；SDK、转换和 COS 为 Mock |
| `ImageToolsTest` | 6 | 本地 HTTP 验证图片接口请求、解析、关键词编码和失败降级；不连接真实供应商 |
| `CodeGenWorkflowTest` | 12 | 真实编译图和节点链，HTML/多文件真实解析落盘、ID 隔离和错误传播；模型和 npm 构建为替代实现 |

使用 JDK 21 和 Maven 3.9.11。当前机器的 PATH / JAVA_HOME 默认指向 JDK 8、Maven 3.6.1，因此验证时显式选择 Java 21，并使用已有 Maven Wrapper 发行版和本地依赖缓存；未修改全局 Java/Maven 配置。

验证命令的核心参数如下，`tmp/langgraph-tests` 需先创建，用作沙箱内可清理的测试临时目录：

```powershell
New-Item -ItemType Directory -Force tmp/langgraph-tests | Out-Null
./mvnw.cmd '-DargLine=-Djava.io.tmpdir=E:/myself/portfolio/code/hui-ai-code-mother/tmp/langgraph-tests' '-Dtest=HuiAiCodeMotherApplicationTests,CodeParserTest,CodeGenWorkflowTest,ImageToolsTest,GeneratedImageToolsTest,ImageCollectionServiceTest,ProjectFileToolsTest,AppControllerStreamErrorTest,VueProjectBuilderTest,JsonMessageStreamHandlerTest,VisualEditPersistenceTest' verify
```

覆盖真实 LangGraph 图的五节点执行、HTML/多文件实际解析落盘、Vue 分支构建调度、应用 ID 隔离、失败降级、生成和构建错误传播、图片请求编码和返回值处理、Logo SDK 参数及结果、架构图上传后清理、Spring Bean 装配、日志依赖兼容，以及现有编辑、流式错误和构建器回归。

模型、图片云服务与 COS 上传使用 Mock 或本地 HTTP 服务；测试没有调用真实付费 AI/图片 API，也不代表外部供应商当前的凭据、接口或可用性已经完成线上联调。原有依赖真实模型、截图服务的测试不在本次可重复回归集合中。
