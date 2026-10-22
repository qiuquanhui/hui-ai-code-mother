# 代码质检与有限修复

更新日期：2026-10-22。

## 功能与流程

[CodeQualityCheckService](../src/main/java/com/hui/huiaicodemother/langgraph4j/ai/CodeQualityCheckService.java) 复用普通 ChatModel，输出 `isValid`、`errors` 和 `suggestions`。[CodeQualityCheckNode](../src/main/java/com/hui/huiaicodemother/langgraph4j/node/CodeQualityCheckNode.java) 在代码生成后检查实际源码。

质检通过后，HTML/多文件直接返回源码目录；Vue 进入构建节点，并校验 `dist/index.html`。未通过时回到代码生成，保留原始需求和图片素材，附加错误与修复建议。质检服务异常会使工作流失败，不当作通过；返回 null 或缺少判断字段时按不通过处理。

普通执行、后续工作流扩展共用 [CodeGenWorkflow](../src/main/java/com/hui/huiaicodemother/langgraph4j/CodeGenWorkflow.java) 的初始化、质检路由和结果校验。也支持从 `originalPrompt` 字符串初始化图状态；无应用 ID 的调用分配独立 ID，但不创建业务应用记录。

## 重试和读取限制

```yaml
langgraph:
  workflow:
    max-generation-attempts: 3
```

次数包含首次生成，允许配置 1～10；达到上限明确失败。质检读取最多 200 个源码文件、合计 256 KiB，剪枝依赖、构建产物、隐藏路径，不跟随符号链接。超过限制或读取异常会失败；空目录不会因拼接标题被当成有源码。

本功能不需要新的 Key，但现有 ChatModel 必须能正确返回结构化 JSON。AI 判断仍不能替代实际构建与运行验收。

## 验证

- `CodeGenWorkflowTest`：拒绝后修复、原需求和错误反馈保留、三次上限、次数配置、质检异常、null/不完整响应、输出目录和三种生成类型。
- `CodeQualityCheckNodeTest`：源码筛选、依赖目录剪枝、空项目、文件数与大小限制。
- `CodeQualityCheckServiceTest`：通过真实 LangChain4j 解析器验证模型 JSON 和无效 JSON 错误行为。

以上使用内存模型或 Mock，没有调用真实云端模型。
