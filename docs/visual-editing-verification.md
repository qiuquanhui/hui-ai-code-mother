# 可视化修改实现与无头验证

更新日期：2026-10-20。最终验证结果：前端类型检查/生产构建通过，21 项后端回归通过，5 组隔离无头场景及真实 DeepSeek 博客副本编辑通过。

## 对照与同步范围

参考仓库：`E:/myself/portfolio/code/yupi/yu-ai-code-mother`。按第 8 期结束的 `695d809` 与第 9 期三个提交 `cce4ad1`、`e05c04c`、`89c7725` 比较，参考增量涉及 16 个文件。进一步按包名、项目名归一化比较两个当前工作树，确认 hui 另有模型配置、构建并发保护、下载、封面等差异，保留这些已有实现。

本次同步：

- 前端：编辑模式、悬浮/选中浮层、元素信息、页面路径/选择器/文本上下文、发送后退出编辑、预览重新加载及监听清理。
- 同源预览：开发与 Vite preview 使用 `/api` 代理，API 默认值为 `/api`；生产部署也需同源反向代理。
- 后端：`BaseTool`、`ToolManager` 与目录读取、文件读取、局部修改、写入、删除五种工具，接入 Vue AI Service 和流式消息格式化。
- 提示词：HTML/多文件修改后输出完整文件；Vue 先查看目录和文件，再按需求增量修改。

## 同步时修复的问题

1. 参考编辑器存在延迟注入竞争、未校验消息来源、SVG className 类型不一致、选择器未转义和退出后残留选择的问题。现使用独立浮层、校验 iframe source/origin、支持 SVG 与 CSS 特殊字符，并在页面重载和组件卸载时清理状态。
2. 文件工具只允许当前 `vue_project_{appId}` 源码目录内的相对路径，拒绝绝对路径、越界、链接、依赖/产物/环境配置目录；按 Windows 大小写不敏感规则检查受限名称。
3. 局部替换拒绝空旧内容和多处匹配；目录读取在遍历前剪枝依赖目录，返回相对路径并限制条数/深度。
4. 工具过程按真实工具名称显示，写入/替换/删除的实际执行结果进入对话历史；未知工具展示消息不再导致空指针异常。
5. Vue 在模型流结束后等待构建成功再发送 `done`，构建失败进入错误分支。SSE 异步请求超时配置为 15 分钟；生产反向代理需相应配置足够的读取超时并关闭 SSE 缓冲。
6. 原生模式保存异常向流传播，显式空 CSS/JS 代码块会清空旧文件。预览静态响应使用 `Cache-Control: no-store`，完成后 iframe 入口使用新版本参数，避免连续修改时显示旧产物。
7. 前端只在收到 `done` 后刷新，连接异常不再按成功处理；关闭连接和移除监听由组件卸载主动完成。保留输入法回车及 Shift+Enter 换行行为。
8. 修正已有用户管理页删除接口的 ID 类型错误，使完整 TypeScript 检查通过。

## 首次集成验证与验证范围

- 平台前端 `npm run build`：Vue/TypeScript 检查与 Vite 生产打包通过。现有的大包体积提示仍存在。
- 后端 Maven：首次 17 项测试通过；补充 SSE 错误协议回归后共 21 项通过，0 失败、0 错误、0 跳过。
- 无头浏览器：Playwright 驱动本机 Edge，访问平台生产构建和隔离 Java 后端。覆盖 5 组场景：
  - HTML 全量修改与连续样式修改。
  - 多文件全量修改、外部 CSS 更新和 JS 加载。
  - Vue 工具读取/局部替换、真实 npm 构建及刷新后的新产物。
  - 非创建者的前端禁用与后端权限拒绝。
  - 模型/SSE 失败后结束加载，保留旧预览，不报告成功。
- 每种模式同时检查悬浮、选择、退出、清除选择、SVG、特殊 CSS 类、编辑期间 iframe 重载、无关消息、链接拦截、元素上下文透传、未选中内容与文件保持不变、页面重新打开后持久化，以及无未处理浏览器异常。

测试服务复用真实 `AppController`、`AppServiceImpl.chatToGenCode`、AI Service 工厂、LangChain4j 工具分发、生成门面、文件工具、流处理器、静态控制器和 Vue 构建器。模型用确定性实现替代；用户、应用和对话数据使用内存测试替身，Redis 记忆存储用内存映射替代。**此结果验证集成链路，不代表外部模型、真实 MySQL/Redis、COS 或生产部署已验收。**

现有其他测试包含真实模型、数据库或截图调用；当前离线回归明确选择文件工具、流处理、原生保存、构建器及控制器错误协议共 21 项测试，没有宣称全量外部依赖测试通过。多用户并发生成隔离、任务回滚、静态接口权限、任意模型输出正确性仍属于后续范围。

## 复现

需要 Java 21、Node.js、已安装的前端依赖、Python Playwright，以及 Playwright Chromium 或 Windows 默认安装位置的 Edge。使用本机有效的 Maven 仓库/镜像设置；以下命令从项目根目录执行：

```powershell
# JAVA_HOME 应指向本机 Java 21
New-Item -ItemType Directory -Force tmp/jvm | Out-Null
$verificationTmp = (Resolve-Path tmp/jvm).Path
.\mvnw.cmd "-DargLine=-Djava.io.tmpdir=$verificationTmp" "-Dtest=ProjectFileToolsTest,JsonMessageStreamHandlerTest,VisualEditPersistenceTest,VueProjectBuilderTest,AppControllerStreamErrorTest" test
npm --prefix hui-ai-code-mother-frontend run build
python scripts/verify_visual_editing.py --start-services --backend-port 8124 --java "$env:JAVA_HOME/bin/java.exe"
```

脚本启动并清理自己创建的 Java/Vite 子进程，上述命令监听 `127.0.0.1:8124` 和 `127.0.0.1:4173`（未指定 `--backend-port` 时后端默认 8123）；端口占用时直接退出，不终止已有服务。只使用 `991001`/`991002`/`991003` 三个测试应用目录；目录已存在且没有 `.visual-edit-fixture` 标记时拒绝覆盖。Vue 测试工程离线复用平台前端的本地依赖。

结果保存在被 Git 忽略的 `tmp/visual-editing/results.json` 与三种模式的 PNG；服务日志为 `tmp/visual-e2e-server.log` 和 `tmp/visual-e2e-frontend.log`。测试源码位于 `src/test/java/com/hui/huiaicodemother/e2e/VisualEditingE2eServer.java`，浏览器脚本位于 `scripts/verify_visual_editing.py`。


## 2026-10-20 真实环境编辑失败修复

用户在原博客 `468620591388770304` 的编辑模式中两次提交标题修改后失败。实际后端日志定位为连接 `https://api.deepseek.com/chat/completions` 时抛出 `SocketException: Permission denied: getsockopt`：由受限执行环境启动的后端没有模型外网访问权限。元素选择和上下文发送均已到达后端。

已在具备网络权限的环境中重新启动真实后端，保留前端开发服务。同时修复 SSE 错误协议：控制器将初始化阶段与流式阶段的异常统一转换为 `generation_error` 事件，失败时不发送 `done`，避免 SSE 请求走 JSON 全局异常处理后再次产生 `HttpMediaTypeNotAcceptableException`。前端显示服务端返回的明确错误并结束加载；服务端只返回受控提示，避免暴露上游地址、密钥或原始错误正文。

新增 `AppControllerStreamErrorTest` 的 4 个用例，覆盖同步网络异常、部分输出后失败、权限失败与正常完成。本轮后端共 21 项回归测试通过，平台前端类型检查和生产构建通过。

使用 `python scripts/verify_live_visual_editing.py --source-app-id 468620591388770304` 做真实回归：复制原博客到临时测试应用，使用真实 DeepSeek、MySQL、Redis、文件修改工具和 npm 构建；无头 Edge 中选中实际 hero 标题并提交“你好，我是derder”，确认预览显示新标题、工具过程写入历史、构建成功且原应用文件未改变。测试账号及其应用、聊天数据库记录在结束时清理，测试产物和截图留在 `tmp/live-visual-editing`。

该脚本会调用真实模型，需要可用的模型网络、密钥和本地数据库；依赖 Python `requests`、`pymysql`、Playwright 以及 Edge。运行前需启动实际前后端。确定性回归脚本增加 `--backend-port 8124`，可在不停止实际 8123 后端的情况下运行。

真实验证记录：`tmp/live-visual-editing/result.json`；截图：`tmp/live-visual-editing/real-model-success.png`。这次真实模型调用和构建已通过；上文“未验收真实外部模型”的说明仅对应最初的隔离回归。
