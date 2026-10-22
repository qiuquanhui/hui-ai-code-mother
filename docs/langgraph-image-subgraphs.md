# 图片收集子图

更新日期：2026-10-22。

## 功能与入口

[CodeGenSubgraphWorkflow](../src/main/java/com/hui/huiaicodemother/langgraph4j/CodeGenSubgraphWorkflow.java) 将内容图片、插画、架构图和 Logo 四类任务分别封装为子图，再作为父图中的并发节点执行。子图写入相应图片状态键，父图在聚合后统一增强提示词并生成、质检和构建。

本功能复用 [显式并发工作流](langgraph-concurrent-images.md) 的状态隔离、执行器生命周期及 [质检修复](langgraph-code-quality.md) 的次数限制。每次调用有自己的图状态和图片结果。

按 `CodeGenSubgraphWorkflow` 类型注入 Spring Service 后，调用 `executeWorkflow(prompt, appId)`。它与普通、显式并发工作流是分别注册的 Service，按具体类型可以独立获取。

## 配置与边界

没有新增 Key 或外部服务，沿用 [图片计划配置](langgraph-image-planning.md)。独立调用不创建应用数据库记录、不自动持久化业务聊天历史、不自动部署；调用者传入业务应用 ID 时应先完成权限检查。

## 验证

`CodeGenSubgraphWorkflowTest` 复用并发图测试契约，验证四个子图同时执行、四类结果完整合并、三种生成类型、单分支/计划失败降级、重复调用隔离及质检失败上限。

测试执行真实编译子图和父图；模型、图片服务和 npm 构建使用替代实现，真实外部服务仍待单独联调。
