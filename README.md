# AI 零代码应用生成平台（hui-ai-code-mother）

> 作者：小辉
>
> 一个以 AI 开发实战 + 后端架构设计为核心的 **企业级 AI 代码生成平台**，持续开发中。

## 项目简介

用户输入需求描述，AI 自动分析并选择合适的生成策略，通过工具调用生成代码文件，采用流式输出实时展示执行过程；生成的应用支持可视化编辑，可以自由选择网页元素并与 AI 对话快速修改页面。

## 技术栈

### 后端

- Java 21 + Spring Boot 3.5
- Spring Boot：Web / AOP
- 数据访问：MyBatis-Flex + HikariCP + MySQL
- 工具库：Hutool
- 接口文档：Knife4j（OpenAPI 3）
- 构建：Maven Wrapper 3.9

### 前端

- Vue 3 + TypeScript
- Vite + OpenAPI 代码生成

## 快速开始

1. 初始化数据库：执行 `sql/create_table.sql`（自动创建 `hui_ai_code_mother` 库和用户表）
2. 修改 `src/main/resources/application.yml` 中的数据库账号密码（默认 root / 123456）
3. 启动后端：运行 `HuiAiCodeMotherApplication`，或命令行 `./mvnw spring-boot:run`
4. 访问接口文档：http://localhost:8123/api/doc.html
5. 前端：进入 `hui-ai-code-mother-frontend` 目录，`npm install` 后 `npm run dev`

## 项目结构

```
hui-ai-code-mother
├── sql                        # 建库建表脚本
├── src/main/java/com/hui/huiaicodemother
│   ├── annotation             # 自定义注解（权限校验 @AuthCheck）
│   ├── aop                    # 切面（权限拦截器）
│   ├── common                 # 通用响应封装
│   ├── config                 # 全局配置（CORS、JSON 等）
│   ├── constant               # 常量定义
│   ├── controller             # 控制器层
│   ├── exception              # 异常处理（全局异常、错误码）
│   ├── generator              # MyBatis-Flex 代码生成器
│   ├── mapper                 # 数据访问层
│   ├── model                  # DTO / 实体 / VO / 枚举
│   └── service                # 业务逻辑层
├── src/main/resources
│   ├── application.yml        # 应用配置
│   └── mapper                 # MyBatis XML
└── hui-ai-code-mother-frontend  # 前端工程
```

## 说明

本项目为个人学习与实践项目，功能持续迭代中。
