# AI 零代码应用生成平台（hui-ai-code-mother）

> 作者：小辉
>
> 一个以 AI 开发实战 + 后端架构设计为核心的 **AI 代码生成平台**，持续开发中。

## 项目简介

用户输入需求描述并创建应用，通过 AI 对话生成代码，采用流式输出实时展示回复和工具执行过程。后端支持 HTML、多文件网页和 Vue 工程三种生成模式，新应用目前默认使用 Vue 工程模式；对话历史保存在 MySQL，模型上下文通过应用级 Redis 记忆管理。

当前提供静态文件预览和目录复制部署。Vue 工程的自动构建与完整发布、自动生成策略选择和页面元素可视化编辑仍在规划中，具体边界见项目文档。

## 项目文档

- [文档索引](docs/README.md)
- [需求文档](docs/requirements.md)
- [方案设计](docs/solution-design.md)
- [项目总结](docs/project-summary.md)

## 技术栈

### 后端

- Java 21 + Spring Boot 3.5
- Spring Boot：Web / AOP
- 数据访问：MyBatis-Flex + HikariCP + MySQL
- AI 编排：LangChain4j + OpenAI 兼容模型接口 + 工具调用
- 会话与上下文：Spring Session Redis + Redis Chat Memory + Caffeine
- 工具库：Hutool
- 接口文档：Knife4j（OpenAPI 3）
- 构建：Maven Wrapper 3.9

### 前端

- Vue 3 + TypeScript
- Vite + OpenAPI 代码生成
- Ant Design Vue + Pinia + Vue Router + Markdown 展示

## 快速开始

1. 准备 Java 21、Node.js、MySQL 和 Redis，初始化空数据库：执行 `sql/create_table.sql`（创建库、用户表、应用表和对话历史表；已有数据库需增量迁移）
2. 新建被 Git 忽略的 `src/main/resources/application-local.yml`，配置数据库、Redis 及 `langchain4j.open-ai.chat-model` / `streaming-chat-model` 的模型地址和密钥；项目默认启用 `local` profile
3. 启动后端：运行 `HuiAiCodeMotherApplication`，或命令行 `./mvnw spring-boot:run`（Windows：`.\mvnw.cmd spring-boot:run`）
4. 访问接口文档：http://localhost:8123/api/doc.html
5. 前端：进入 `hui-ai-code-mother-frontend` 目录，`npm ci` 后 `npm run dev`
6. 如需访问部署站点，另行配置静态服务托管 `tmp/code_deploy`，并使前后端部署域名一致；详细配置与当前验证限制见 [方案设计](docs/solution-design.md) 和 [项目总结](docs/project-summary.md)

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
│   ├── core                   # 生成门面、解析器、保存器与流处理器
│   ├── exception              # 异常处理（全局异常、错误码）
│   ├── generator              # MyBatis-Flex 代码生成器
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
