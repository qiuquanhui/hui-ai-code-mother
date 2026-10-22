# AI 零代码应用生成平台（hui-ai-code-mother）

> 作者：小辉
>
> 一个以 AI 开发实战 + 后端架构设计为核心的 **AI 代码生成平台**，持续开发中。

## 项目简介

用户输入需求描述并创建应用，AI 路由器根据需求选择 HTML、多文件网页或 Vue 工程模式，再通过流式对话生成和修改代码。对话历史保存在 MySQL，模型上下文通过应用级 Redis 记忆管理。

当前提供静态文件预览、源码 ZIP 下载和目录复制部署。Vue 工程在流式对话结束前完成构建，部署时构建并发布 `dist`；同一后端进程内，同目录的重叠构建请求会等待并复用结果。部署后异步截取站点图片、上传腾讯云 COS 并更新封面。支持在预览中选中元素并通过对话修改：HTML/多文件全量保存，Vue 使用目录、读取、局部替换、写入和删除工具增量修改。构建隔离、版本化发布和路由失败兜底仍待完善，具体边界见项目文档。

## 项目文档

- [文档索引](docs/README.md)
- [可视化修改实现与无头验证](docs/visual-editing-verification.md)
- [LangGraph 工作流、图片配置与验证范围](docs/langgraph-sync-verification.md)
- [需求文档](docs/requirements.md)
- [方案设计](docs/solution-design.md)
- [项目总结](docs/project-summary.md)
- [Vue 构建与故障排查](docs/vue-build-troubleshooting.md)
- [AI 路由测试与 Brotli 响应截断排查](docs/ai-routing-troubleshooting.md)

## 技术栈

### 后端

- Java 21 + Spring Boot 3.5
- Spring Boot：Web / AOP
- 数据访问：MyBatis-Flex + HikariCP + MySQL
- AI 编排：LangChain4j + OpenAI 兼容模型接口 + 工具调用
- 独立生成工作流：LangGraph4j 1.6.0-rc2；图片工具使用 Pexels、unDraw、DashScope SDK 2.21.1 和 Mermaid CLI
- 会话与上下文：Spring Session Redis + Redis Chat Memory + Caffeine
- 工具库：Hutool
- 网页截图：Selenium + WebDriverManager + Chrome
- 封面存储：腾讯云 COS
- 接口文档：Knife4j（OpenAPI 3）
- 构建：Maven Wrapper 3.9

### 前端

- Vue 3 + TypeScript
- Vite + OpenAPI 代码生成
- Ant Design Vue + Pinia + Vue Router + Markdown 展示

## 快速开始

1. 准备 Java 21、Node.js（满足平台前端及生成工程各自的版本要求）、MySQL 和 Redis；确保后端进程的 PATH 能找到 `node` 和 `npm`。初始化空数据库：执行 `sql/create_table.sql`（创建库、用户表、应用表和对话历史表；已有数据库需增量迁移）
2. 新建被 Git 忽略的 `src/main/resources/application-local.yml`，配置数据库、Redis 及 `langchain4j.open-ai.chat-model` / `streaming-chat-model` 的模型地址和密钥；项目默认启用 `local` profile
3. 在允许访问模型服务的环境中启动后端：运行 `HuiAiCodeMotherApplication`，或命令行 `./mvnw spring-boot:run`（Windows：`.\mvnw.cmd spring-boot:run`）
4. 访问接口文档：http://localhost:8123/api/doc.html
5. 前端：进入 `hui-ai-code-mother-frontend` 目录，`npm ci` 后 `npm run dev`；默认通过 `/api` 同源代理访问后端，生产环境也需把 `/api` 代理到后端，以支持 iframe 元素编辑
6. 如需访问部署站点，另行配置静态服务托管 `tmp/code_deploy`，并使前后端部署域名一致；详细配置与当前验证限制见 [方案设计](docs/solution-design.md) 和 [项目总结](docs/project-summary.md)
7. 在 `application-local.yml` 配置 `cos.client` 的 `host`、`secretId`、`secretKey`、`region`、`bucket`，其中 `host` 使用带 `https://` 的完整域名；当前 COS Bean 在启动时创建，密钥仅保存在本机配置。部署封面生成还需要安装 Chrome，并允许 WebDriverManager 获取匹配驱动；后端必须能访问返回的部署地址

### LangGraph 图片功能配置

使用真实图片功能前，需准备以下配置；缺少 Key 不影响启动，但对应图片功能会返回空列表。

| 功能 | 必需配置 | 未配置或失败时的行为 |
| --- | --- | --- |
| Pexels 内容图片搜索 | `PEXELS_API_KEY` | 缺少 Key 时不发起请求，返回空列表 |
| DashScope Logo 生成 | `DASHSCOPE_API_KEY` | 缺少 Key 时不发起请求，返回空列表 |
| Mermaid 架构图生成并上传 | PATH 可执行 `mmdc` / Windows `mmdc.cmd`，以及 `cos.client` 配置 | 转换或上传失败时返回空列表 |
| 图片收集 AI、类型路由和代码生成 | 原有 LangChain4j 模型地址、密钥和 MySQL/Redis 配置 | 图片收集失败可降级；代码生成失败会终止工作流 |

在 IDEA 的后端 **Run/Debug Configurations → Environment variables** 中添加 `PEXELS_API_KEY=你的Key` 和 `DASHSCOPE_API_KEY=你的Key`，保存并重启后端。命令行启动则在同一 PowerShell 会话设置：

```powershell
$env:PEXELS_API_KEY = '你的PexelsKey'
$env:DASHSCOPE_API_KEY = '你的DashScopeKey'
.\mvnw.cmd spring-boot:run
```

Logo 默认模型为 `wan2.2-t2i-flash`，可通过 `DASHSCOPE_IMAGE_MODEL` 覆盖。也可以将 `pexels.api-key`、`dashscope.api-key`、`dashscope.image-model` 写入被 Git 忽略的 `application-local.yml`；密钥不得提交。

工作流目前通过后端 `CodeGenWorkflow.executeWorkflow(prompt, appId)` 独立调用，尚未接入现有聊天 SSE 接口。2026-10-22 提交前重新执行 Maven `verify`，48 项定向测试及打包通过；模型、图片云服务、COS 上传使用 Mock／本地 HTTP，**真实云服务尚未联调，不能据此认为配置密钥后已验收通过**。四个 LangGraph 测试类的用例数量和完整边界见 [同步与验证记录](docs/langgraph-sync-verification.md)。

可视化编辑已通过 21 项后端回归、三种模式无头测试及真实模型博客副本验证。模型连接失败时会显示明确错误；排障和复现步骤见 [可视化修改记录](docs/visual-editing-verification.md)。

普通聊天模型已配置 `custom-headers.Accept-Encoding: gzip`，避免当前 Brotli 解压依赖导致同步响应被截断。出现 HTTP 200 但 `JsonEOFException` 的情况时，参见 [排查记录](docs/ai-routing-troubleshooting.md)。

## 项目结构

```
hui-ai-code-mother
├── docs                       # 需求、方案设计、项目总结
├── sql                        # 建库建表脚本
├── src/main/java/com/hui/huiaicodemother
│   ├── annotation             # 自定义注解（权限校验 @AuthCheck）
│   ├── aop                    # 切面（权限拦截器）
│   ├── ai                     # AI 服务工厂、消息模型与工具
│   ├── common                 # 通用响应封装
│   ├── config                 # 全局配置（CORS、JSON 等）
│   ├── constant               # 常量定义
│   ├── controller             # 控制器层
│   ├── core                   # 生成门面、解析器、保存器、流处理器与 Vue 构建器
│   ├── exception              # 异常处理（全局异常、错误码）
│   ├── generator              # MyBatis-Flex 代码生成器
│   ├── langgraph4j            # 独立五节点生成工作流、状态、图片 AI 服务与工具
│   ├── mapper                 # 数据访问层
│   ├── model                  # DTO / 实体 / VO / 枚举
│   └── service                # 业务逻辑层
├── src/main/resources
│   ├── application.yml        # 应用配置
│   ├── mapper                 # MyBatis XML
│   └── prompt                 # 不同生成模式的系统提示词
└── hui-ai-code-mother-frontend  # 前端工程
```

## 说明

本项目为个人学习与实践项目，功能持续迭代中。
