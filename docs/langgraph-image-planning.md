# 图片计划与节点内部并行收集

更新日期：2026-10-22。

## 功能与入口

[ImageCollectionPlanService](../src/main/java/com/hui/huiaicodemother/langgraph4j/ai/ImageCollectionPlanService.java) 根据网站需求返回结构化任务计划，覆盖内容图片、插画、Mermaid 架构图和 Logo。任务模型为 [ImageCollectionPlan](../src/main/java/com/hui/huiaicodemother/langgraph4j/model/ImageCollectionPlan.java)。

普通工作流中的 [ImageCollectorNode](../src/main/java/com/hui/huiaicodemother/langgraph4j/node/ImageCollectorNode.java) 先请求一次计划，再使用本次执行独享的四线程池并行执行计划中的图片任务。结束时关闭线程池，将成功结果汇总到上下文，用于后续提示词增强。单个任务失败不丢弃其他成功结果；计划失败可以降级为无图片。

本功能是节点内部并行执行，不改变外层工作流的生成类型选择或应用 ID 隔离规则。原 `ImageCollectionService` 保留兼容，当前图片节点改用结构化计划服务。

## 配置

- 图片计划复用现有普通 ChatModel，不需要新增模型 Key。
- 真实 Pexels 搜图需要 `PEXELS_API_KEY`；Logo 生成需要 `DASHSCOPE_API_KEY`，默认图片模型为 `wan2.2-t2i-flash`。
- 架构图需要 Mermaid CLI 和现有 COS 配置；unDraw 仍依赖外部接口可用。
- 缺少图片 Key 时对应工具返回空列表，不代表真实图片功能已验收。Key 仅放在环境变量或 Git 忽略的本机配置中。

完整凭据示例见 [图片功能配置](langgraph-sync-verification.md#启用功能前的配置)。

## 验证

`CodeGenWorkflowTest` 验证图片计划只调用一次、四类任务同时执行、某个任务失败后保留其他结果、图片增强提示词及 HTML/多文件实际落盘。`ImageCollectionPlanServiceTest` 使用真实 LangChain4j 输出解析器验证任务列表与 record 参数，模型由内存 Mock 提供。

本功能测试不调用真实付费模型、图片 API 或云上传；真实供应商配置与可用性仍需单独联调。
