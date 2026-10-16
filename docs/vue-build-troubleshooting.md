# Vue 构建与故障排查

更新日期：2026-10-16。本文记录当前实现和本次本地验证；不表示全部生成任务或生产环境已验收。

## 1. 构建流程

1. Vue 文件工具把源码写入 `tmp/code_output/vue_project_{appId}`。
2. `JsonMessageStreamHandler` 在生成流完成后调用 `buildProjectAsync`，虚拟线程执行构建；生成流的 `done` 事件不等待构建结束。
3. `VueProjectBuilder` 检查项目目录及 `package.json`，执行 `npm install`（300 秒超时）、`npm run build`（180 秒超时），最后检查 `dist/index.html`。
4. 前端识别 `vue_project` 类型，预览 `/api/static/vue_project_{appId}/dist/index.html`。首次构建未完成时，预览可能暂时为空或返回 404，完成后需刷新；目前没有前端可订阅的构建状态。
5. 点击部署时，`AppServiceImpl` 调用同一构建器，成功后仅复制 `dist` 内容到 `tmp/code_deploy/{deployKey}`，再更新部署标识及时间。
6. Nginx 等静态服务映射 `tmp/code_deploy`，部署域名与后端 `AppConstant.CODE_DEPLOY_HOST`、前端 `VITE_DEPLOY_DOMAIN` 保持一致。后端预览接口读取的是生成目录，不托管部署目录。

构建器使用后端进程的 Node.js/npm 环境。Windows 通过 `cmd.exe /d /c npm.cmd` 启动 npm；修改 Java 代码后需重启后端，进程环境变量变更后同样需要重启。

## 2. 同项目并发构建保护

自动构建和部署构建可能重叠。构建器先取得项目规范路径，以路径 URI 为键，用 `ConcurrentHashMap` 保存正在进行的 `CompletableFuture<Boolean>`：

- 首个请求执行安装、打包和产物校验。
- 重叠请求等待并复用本次构建结果，不再次执行安装和打包。
- 构建结束后完成 Future 并移除记录；成功结果不长期缓存，后续请求可重新构建，失败后也可重试。
- 不同项目使用不同键，仍可并行构建。

该保护仅作用于同一后端进程内的构建器实例。它不提供跨 JVM 的锁，也不阻止模型在构建期间修改源码，不等同于应用级生成互斥、版本快照或资源隔离。

## 3. npm 输出与进程生命周期

每个生成项目根目录保存两份日志：

| 文件 | 内容 |
| --- | --- |
| `npm-install.log` | 最近一次依赖安装的 stdout 和 stderr |
| `npm-build.log` | 最近一次打包的 stdout 和 stderr |

每次对应命令覆盖其日志。输出直接重定向到文件，避免 Java 只等待退出而不消费输出管道，导致 npm 因管道写满而阻塞。命令失败或超时时，后端输出日志路径及最后至多 16 KiB 内容；超时或构建线程中断时终止仍在运行的命令及其子进程，中断状态保留。

不要仅依据业务异常“Vue 项目构建失败，请检查代码和依赖”判断原因，应先查看对应 npm 日志。

## 4. 2026-10-16 故障记录

原项目 `vue_project_468620591388770304` 在 16:32:16 启动自动安装，16:32:59 部署请求再次启动安装。第一次安装在 16:33:12 以 `-4051` 退出，第二次安装成功后打包以 `1` 退出。

已确认的证据：

- 在本机 Windows Node.js 中，`require('node:util').getSystemErrorName(-4051)` 返回 `ENOTEMPTY`，表示目录非空。
- 对原项目单独执行 `npm run build`，Vite 报 `Cannot find module .../node_modules/vue/index.js`；Vue 包目录确实缺少该入口。
- 备份原 `node_modules`，按已有锁文件重新安装依赖后，原源码无需修改即可成功构建。

结合重叠安装、目录错误和包文件缺失，最可能的原因是同目录并发安装造成依赖目录损坏。原构建器未保存 npm 输出，无法还原第一次安装失败时的全部 npm 诊断，不把该推断表述为完整取证结论。

删除 npm 缓存本身通常只意味着需要重新下载依赖，不会直接删除项目内的 `node_modules/vue/index.js`。安装耗时变长可能扩大并发重叠窗口；网络或权限失败仍需依据日志单独判断。参考 [npm 缓存说明](https://docs.npmjs.com/cli/v11/commands/npm-cache/)。

## 5. 恢复与排查步骤

1. 确认同一目录没有自动构建、部署构建或手动 npm 命令仍在执行。多实例场景需逐实例检查；当前进程内合并不能保护手动命令或其他实例。
2. 在项目根目录检查 `package.json`、锁文件及两份 npm 日志。区分网络/权限错误、依赖缺失与源码编译错误。
3. 若已有证据表明依赖损坏，先备份该项目的 `node_modules`，保留业务源码和锁文件，避免直接修改依赖版本来掩盖问题。
4. 切换到生成项目目录。存在有效且与清单一致的 `package-lock.json` 时运行 `npm ci`，之后运行 `npm run build`；没有有效锁文件时使用 `npm install` 并检查结果。`npm ci` 会重建依赖目录，因此必须先完成上一步。
5. 确认生成 `dist/index.html`，预览页面能加载 JS/CSS，再部署并访问返回地址。
6. 若部署接口成功但返回地址连接被拒绝，检查静态服务是否启动、监听端口是否匹配。此次本机已有 Nginx 配置，启动后部署地址可访问；这与 npm 构建是两个独立环节。

## 6. 验证方式与结果

在 Java 21 环境下，从仓库根目录运行：

```powershell
.\mvnw.cmd -Dtest=VueProjectBuilderTest test
```

使用项目 Maven Wrapper 3.9.11。此次本机默认 Maven 3.6.1 不满足编译插件要求，默认 Java 路径也存在旧 JDK 配置；不要把本机默认命令失败直接等同于项目源码无法编译。

六项测试均通过：同目录规范路径别名共享一次构建、不同项目并行、失败后可重试、大量 stdout/stderr 不阻塞且保存、非零退出保留诊断、超时终止进程。这些测试不启动 Spring、不连接数据库/Redis，也不调用真实模型。

另行完成两层实测：

- 使用修复后的 Java 构建器，对原故障项目同时发起两个请求。日志仅出现一轮安装/打包，另一请求等待复用，两个请求均成功。
- 使用本地平台及测试账号，提交“创建一个现代化的个人博客网站，代码量不超过200行。”，完成生成、构建、iframe 预览和复制部署；分类筛选和文章详情可用，Nginx 提供的部署页面可访问。此页面测试使用当时运行的后端；修复后构建器通过上一项单独实测验证，运行中的后端仍需重启才能加载新代码。

本次模型生成的 `src` 目录约 926 行，没有遵守 200 行限制；不能把构建成功视为提示词约束全部满足。构建器目前也没有服务端行数硬校验。

## 7. 实现入口

- [VueProjectBuilder](../src/main/java/com/hui/huiaicodemother/core/builder/VueProjectBuilder.java)
- [VueProjectBuilderTest](../src/test/java/com/hui/huiaicodemother/core/builder/VueProjectBuilderTest.java)
- [JsonMessageStreamHandler](../src/main/java/com/hui/huiaicodemother/core/handler/JsonMessageStreamHandler.java)
- [AppServiceImpl](../src/main/java/com/hui/huiaicodemother/service/impl/AppServiceImpl.java)
- [前端预览地址](../hui-ai-code-mother-frontend/src/config/env.ts)

隔离构建、多实例协调、生成与构建之间的版本一致性、原子发布以及构建状态展示仍属于后续工作。
