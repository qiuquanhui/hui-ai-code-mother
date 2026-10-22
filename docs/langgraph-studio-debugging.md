# Studio 可视化调试

更新日期：2026-10-22。

## 依赖与开关

新增 `org.bsc.langgraph4j:langgraph4j-studio-springboot:1.6.0-rc2`，与参考项目和现有 core 版本一致。配置入口为 [LangGraphStudioSampleConfig](../src/main/java/com/hui/huiaicodemother/langgraph4j/config/LangGraphStudioSampleConfig.java)。

```yaml
langgraph:
  studio:
    enabled: false
```

默认关闭，不注册 Studio `/init`、`/stream` Servlet。调试时显式启用，并把后端绑定本机，例如启动参数 `--langgraph.studio.enabled=true --server.address=127.0.0.1`。图输入字段为 `originalPrompt`，初始化节点将其转为隔离的工作流上下文。

Studio 使用依赖包提供的 Servlet，不经过 [工作流演示控制器](langgraph-workflow-streaming.md) 的管理员检查，所以仅用于本地调试。其执行仍会使用真实模型和图片能力；Key、Mermaid/COS 等前提见 [图片配置](langgraph-image-planning.md)。

## 验证

`LangGraphStudioConfigTest` 验证默认不注册 Servlet、显式开启后的注册及提示词输入、图编译；`CodeGenWorkflowTest` 验证从 `originalPrompt` 输入执行完整图；启动测试验证三个工作流与 AI 服务装配。

本功能测试不发起真实云端请求；依赖版本和最终 JAR 中的 Studio 内容另行检查。
