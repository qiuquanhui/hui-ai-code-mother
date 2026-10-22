# 显式并发图片分支

更新日期：2026-10-22。

## 功能与入口

[CodeGenConcurrentWorkflow](../src/main/java/com/hui/huiaicodemother/langgraph4j/CodeGenConcurrentWorkflow.java) 在图片计划后，将内容图片、插画、架构图和 Logo 分发到四个独立节点；四个分支结束后由聚合节点合并，再进入提示词增强、路由、生成、质检和 Vue 构建。

[并发节点](../src/main/java/com/hui/huiaicodemother/langgraph4j/node/concurrent/ImagePlanNode.java) 使用独立状态键保存各类图片；[聚合节点](../src/main/java/com/hui/huiaicodemother/langgraph4j/node/concurrent/ImageAggregatorNode.java) 统一合并。分支不同时覆盖同一个可变 `WorkflowContext`；每次执行拥有并关闭自己的执行器，重复执行不复用上次图片结果。

可以按具体类型注入此 Spring Service，并调用 `executeWorkflow(prompt, appId)`。默认按 `CodeGenWorkflow` 注入时仍选择普通工作流。该入口不会自动创建业务应用或部署产物。

## 失败与配置

图片计划失败可以无图片继续；某个图片供应商失败不影响其他分支结果。代码生成、质检和构建仍遵循统一的失败传播与最大生成次数。

本功能没有新增 Key，继续使用 Pexels/DashScope、Mermaid/COS 和现有 ChatModel 配置。运行前先阅读 [图片计划与配置](langgraph-image-planning.md) 和 [质检限制](langgraph-code-quality.md)。

## 验证

`CodeGenConcurrentWorkflowTest` 使用并发屏障验证四个分支确实重叠执行，并覆盖 HTML、多文件、Vue 三种生成类型、分支失败后的合并、计划失败降级、连续执行隔离和质检重试上限。

图和文件处理实际执行；模型、图片供应商和 npm 构建由替代实现提供，未完成真实云端联调。
