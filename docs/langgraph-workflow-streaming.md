# 工作流 HTTP 与流式事件

更新日期：2026-10-22。

## 接口与权限

[WorkflowSseController](../src/main/java/com/hui/huiaicodemother/controller/WorkflowSseController.java) 提供独立工作流入口，三个接口都校验管理员登录态，使用普通 `CodeGenWorkflow`：

| 方法 | 当前完整路径 | 参数 | 响应 |
| --- | --- | --- | --- |
| POST | `/api/workflow/execute` | `prompt` | `WorkflowContext` |
| GET | `/api/workflow/execute-flux` | `prompt` | `text/event-stream` |
| GET | `/api/workflow/execute-sse` | `prompt` | `text/event-stream` |

接口为独立演示用途，不替换现有 `/app` 聊天生成流程；它们分配独立应用 ID，不创建业务应用记录或自动部署。真实调用会使用模型和图片服务，必须先完成 [凭据配置](langgraph-image-planning.md)。

## 事件与生命周期

事件顺序为 `workflow_start`、若干 `step_completed`，最后发送 `workflow_completed` 或 `workflow_error`。这里提供节点进度，不输出模型逐 Token 事件。失败不发送成功事件，也不回显底层模型地址或 Key。

HTTP Flux 使用结构化 `ServerSentEvent`，避免二次包装 SSE 文本；内部 `executeWorkflowWithFlux` 保留原始 SSE 帧格式。无 ID 的冷流每次订阅分配不同 ID。SseEmitter 的完成、超时和连接错误释放订阅，取消时中断执行线程；第三方已经接受的任务能否立即停止取决于供应商。

## 演示页

管理员登录后，可同源打开 `/api/test-flux-workflow.html` 和 `/api/test-sseemitter-workflow.html`。页面使用相对 URL 和已有 Cookie，没有硬编码主机端口。

## 验证

- `WorkflowStreamingTest`：开始/完成/错误事件、质检重试耗尽、取消中断、冷流 ID 隔离及原始帧格式。
- `WorkflowSseControllerTest`：实际 Spring MVC 异步编码、JSON 帧、禁止重复包装、失败终态、管理员权限和 SSE 超时取消。

测试执行真实 MVC 传输编码和节点图，外部服务使用 Mock；演示页通过 JavaScript 语法检查，但没有浏览器人工验收。
