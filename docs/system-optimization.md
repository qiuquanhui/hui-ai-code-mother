# 系统优化：限流、内容安全、缓存与 AI 并发

更新日期：2026-10-23。本文记录本轮"系统优化"迭代的四组功能与两项稳定性修复，全部基于当前仓库源码整理；"已实现"表示存在对应源码并完成基本链路验证，不代表生产环境验收。

## 1. 功能总览

| 优化方向 | 实现方式 | 主要代码 | 状态 |
| --- | --- | --- | --- |
| AI 调用并发 | 路由/流式/推理模型改为 prototype 多例，按次获取 | `config/RoutingAiModelConfig` 等 3 个配置类、`ai/*Factory` | 已实现，浏览器全链路验证 |
| 安全 - Prompt 审查 | LangChain4j Input/Output Guardrail 内容审查 | `ai/guardrail/` | 已实现基础规则，仅接入默认生成分支 |
| 安全 - 接口限流 | Redisson RRateLimiter + AOP 注解式限流 | `ratelimiter/` | 已实现，当前仅覆盖 SSE 生成接口 |
| 并发 - Redis 缓存 | Spring Cache + Redis 缓存精选列表分页 | `config/RedisCacheManagerConfig`、`utils/CacheKeyUtils` | 已实现，无主动失效 |
| 稳定性 - 响应截断 | 排除 Brotli 间接依赖 + 全模型 gzip 协商 | `pom.xml`、模型配置类、`application.yml` | 已修复并端到端验证 |
| 稳定性 - 错误处理 | SSE 请求异常转为 `business-error` 事件返回 | `exception/GlobalExceptionHandler` | 已实现基础链路 |

## 2. AI 调用并发优化（多例模型）

此前路由、流式与推理模型均为单例，多用户并发对话共享同一模型实例。本轮将三类模型改为 `@Scope("prototype")` 多例 Bean：

- [RoutingAiModelConfig](../src/main/java/com/hui/huiaicodemother/config/RoutingAiModelConfig.java)：`routingChatModelPrototype`
- [StreamingChatModelConfig](../src/main/java/com/hui/huiaicodemother/config/StreamingChatModelConfig.java)：`streamingChatModelPrototype`
- [ReasoningStreamingChatModelConfig](../src/main/java/com/hui/huiaicodemother/config/ReasoningStreamingChatModelConfig.java)：`reasoningStreamingChatModelPrototype`

工厂类（[AiCodeGenTypeRoutingServiceFactory](../src/main/java/com/hui/huiaicodemother/ai/AiCodeGenTypeRoutingServiceFactory.java)、[AiCodeGeneratorServiceFactory](../src/main/java/com/hui/huiaicodemother/ai/AiCodeGeneratorServiceFactory.java)、langgraph4j 下三个服务工厂）在每次创建 AI 服务时，通过注入的配置类代理调用 prototype Bean 方法，获取全新模型实例；`AppServiceImpl` 创建应用时同样改为按次新建路由服务。

实现细节：原方案在 `@Bean` 工厂方法内通过 `SpringContextUtil.getBean` 静态获取多例，依赖 Bean 初始化顺序，曾在启动期抛出"Spring 上下文尚未初始化"。改为直接注入配置类后，依赖顺序交给 Spring 管理，多例语义不变。

## 3. 安全性：AI 内容审查（Guardrail）

新增 [PromptSafetyInputGuardrail](../src/main/java/com/hui/huiaicodemother/ai/guardrail/PromptSafetyInputGuardrail.java)（输入护轨）与 [RetryOutputGuardrail](../src/main/java/com/hui/huiaicodemother/ai/guardrail/RetryOutputGuardrail.java)（输出护轨），在 [AiCodeGeneratorServiceFactory](../src/main/java/com/hui/huiaicodemother/ai/AiCodeGeneratorServiceFactory.java) 构建 AI 服务时接入：

- 输入审查：长度超过 1000 字、空输入、命中敏感词（诱导忽略指令、破解、越狱等）或注入攻击正则（`ignore previous instructions`、`system: you are` 等模式）时直接拒绝。
- 输出审查：响应为空或过短（少于 10 字）、包含敏感信息关键词（密码、token、api key 等）时通过 `reprompt` 要求模型重新生成。

适用边界：护轨已接入全部两个生成分支（Vue 工程推理模型与 HTML/多文件默认模型）；敏感词与注入规则为代码内硬编码，暂未中心化配置。

## 4. 安全性：接口限流（Redisson）

新增 `ratelimiter` 模块，基于 Redisson `RRateLimiter` 实现分布式限流：

| 组件 | 说明 |
| --- | --- |
| [@RateLimit](../src/main/java/com/hui/huiaicodemother/ratelimiter/annotation/RateLimit.java) 注解 | 支持 `key` 前缀、`rate` 窗口内次数、`rateInterval` 窗口秒数、`limitType`（USER/IP/API）、`message` 提示语 |
| [RateLimitAspect](../src/main/java/com/hui/huiaicodemother/ratelimiter/aspect/RateLimitAspect.java) | `@Before` 切面，按维度拼接 `rate_limit:` 前缀键；`trySetRate(OVERALL)` 设置速率，`tryAcquire` 失败抛出 `TOO_MANY_REQUEST` |
| [RedissonConfig](../src/main/java/com/hui/huiaicodemother/ratelimiter/config/RedissonConfig.java) | 单机模式，地址与库号读取 `spring.data.redis.*`；限流器键 1 小时过期 |

当前接入范围：`GET /app/chat/gen/code`（SSE 生成接口）用户级 5 次 / 60 秒，超限返回 `TOO_MANY_REQUEST(42900)`。SSE 请求命中限流或系统异常时，[GlobalExceptionHandler](../src/main/java/com/hui/huiaicodemother/exception/GlobalExceptionHandler.java) 以 `text/event-stream` 返回 `business-error` 事件；前端 [AppChatPage.vue](../hui-ai-code-mother-frontend/src/pages/app/AppChatPage.vue) 监听该事件，展示具体错误并结束生成状态。

适用边界：其余接口（登录、创建应用等）尚未加注解；限流参数为代码内硬编码，未提供管理端配置。

## 5. 并发：Redis 缓存优化

- [HuiAiCodeMotherApplication](../src/main/java/com/hui/huiaicodemother/HuiAiCodeMotherApplication.java) 开启 `@EnableCaching`。
- [RedisCacheManagerConfig](../src/main/java/com/hui/huiaicodemother/config/RedisCacheManagerConfig.java)：默认缓存 TTL 30 分钟、禁用 null 值缓存、key 使用 String 序列化；`good_app_page` 独立配置 5 分钟过期（value 的 JSON 序列化配置已注释，当前为 JDK 默认序列化）。
- [CacheKeyUtils](../src/main/java/com/hui/huiaicodemother/utils/CacheKeyUtils.java) 统一生成缓存键。
- [AppController](../src/main/java/com/hui/huiaicodemother/controller/AppController.java) 精选应用分页查询加 `@Cacheable(value = "good_app_page", condition = "pageNum <= 10")`，仅缓存前 10 页。

适用边界：管理员调整精选优先级后，缓存最多延迟 5 分钟生效，暂无主动失效；`good_app_page` 以外查询未接入缓存。

## 6. 稳定性：Brotli 响应截断根因修复

此前仅普通聊天模型配置了 gzip 规避（详见 [AI 路由排查记录](ai-routing-troubleshooting.md)），路由与流式模型仍会触发 `JsonEOFException`（HTTP 200 但响应体只剩 `{`）。本轮闭环：

1. `pom.xml` 中 `webdrivermanager` 依赖排除间接依赖 `org.brotli:dec:0.1.2`，classpath 不再存在 Brotli 解码器，客户端不再协商 `br` 响应（根治）。
2. 三个自建模型配置类新增 `customHeaders` 字段并在 Builder 中透传，`application.yml` 为全部四类模型统一配置 `custom-headers.Accept-Encoding: gzip`（保险层，依赖变化时依然生效）。

完整根因分析、A/B 复现与修复验证见排查记录第 8 节。

## 7. 验证情况

- `./mvnw compile`（JDK 21）通过。
- 2026-10-23 浏览器端到端验证：登录 → 首页输入"创建一个个人博客网站" → AI 路由返回多文件模式 → 流式生成完整代码 → 预览区渲染成功；`POST /api/app/add` 返回 `code:0`。
- Redisson 限流器对本地 Redis（5.0.14）的 `expire / trySetRate / tryAcquire` 操作独立验证通过；SSE 生成链路在验证中未触发限流。

## 8. 待完善

- 限流扩展到登录、创建应用等接口；限流参数配置化；补充超限场景的自动化验证。
- Guardrail 接入 Vue 工程生成分支；敏感词与注入规则改为中心化配置。
- 缓存增加主动失效（精选变更时驱逐）；启用 value 的 JSON 序列化并回归反序列化兼容性。
- Brotli 排除后，若未来升级 LangChain4j / HttpClient 组合，可重新评估是否恢复压缩协商。

返回：[项目文档索引](README.md)。
