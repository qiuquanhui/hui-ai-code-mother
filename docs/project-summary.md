# 项目总结

更新日期：2026-10-22。本总结基于源码、Git 历史和实际检查，不将未执行用例视为验收通过。历史检查保留日期，各轮验证的范围单独记录。

## 1. 阶段成果

项目已从用户与应用 CRUD 扩展到 AI 生成应用的基础流程：自然语言需求创建应用，流式展示模型回复和文件工具执行，保留对话历史，支持静态预览及文件复制部署。

| 方向 | 当前成果 | 主要源码入口 |
| --- | --- | --- |
| 用户与权限 | 注册登录、用户管理、Redis Session、管理员注解校验 | [UserServiceImpl](../src/main/java/com/hui/huiaicodemother/service/impl/UserServiceImpl.java) |
| 应用管理 | 创建、个人列表、精选列表、编辑、删除与管理员维护 | [AppController](../src/main/java/com/hui/huiaicodemother/controller/AppController.java) |
| AI 生成 | HTML、多文件、Vue 工程三种后端生成方式 | [AiCodeGeneratorFacade](../src/main/java/com/hui/huiaicodemother/core/AiCodeGeneratorFacade.java) |
| 独立工作流 | LangGraph4j 五节点编排及图片工具；尚未接入聊天 SSE，真实云服务未联调 | [工作流入口](../src/main/java/com/hui/huiaicodemother/langgraph4j/CodeGenWorkflow.java) |
| 需求路由 | 创建应用时用普通模型选择生成类型，对话页和详情展示类型 | [路由服务](../src/main/java/com/hui/huiaicodemother/ai/AiCodeGenTypeRoutingService.java) |
| 上下文管理 | 应用级 AI Service、Redis 记忆、MySQL 历史恢复、Caffeine 缓存 | [AI 工厂](../src/main/java/com/hui/huiaicodemother/ai/AiCodeGeneratorServiceFactory.java) |
| 工具过程展示 | 定制流式工具回调，内部消息转成可读文本和代码块 | [JsonMessageStreamHandler](../src/main/java/com/hui/huiaicodemother/core/handler/JsonMessageStreamHandler.java) |
| 前端交互 | 首页、应用对话、元素可视化修改、应用信息编辑、三类管理页面 | [路由](../hui-ai-code-mother-frontend/src/router/index.ts) |
| 产物访问 | Vue 自动构建、dist 预览/部署；静态文件复制部署并记录访问标识 | [AppServiceImpl](../src/main/java/com/hui/huiaicodemother/service/impl/AppServiceImpl.java) |
| 构建可靠性 | 同目录重叠构建复用、npm 输出落盘、失败诊断、超时/中断进程清理及六项回归 | [VueProjectBuilder](../src/main/java/com/hui/huiaicodemother/core/builder/VueProjectBuilder.java) |
| 源码交付 | 创建者下载生成目录 ZIP，过滤依赖、构建产物及指定文件 | [ProjectDownloadServiceImpl](../src/main/java/com/hui/huiaicodemother/service/impl/ProjectDownloadServiceImpl.java) |
| 部署封面 | 部署后虚拟线程截取网页，压缩上传 COS 并更新封面，前端处理 URL 协议和卡片加载失败 | [ScreenshotServiceImpl](../src/main/java/com/hui/huiaicodemother/service/impl/ScreenshotServiceImpl.java) |

2026-10-16 在当时固定 Vue 类型的创建流程下，自动构建、dist 预览和本地静态部署已打通一个博客案例。当前创建流程已改为 AI 选择类型；新路由的三条模型调用已验证，应用创建到下载、截图上传的组合链路尚未重新验收。构建隔离、任务状态、跨进程协调及原子版本发布仍待补齐。

## 2. 最近迭代记录

2026-10-14 的文档整理记录了以下 6 次实现迭代；本表保留其技术演进背景，不表示当前远程同步状态。

| 提交 | 主题 | 作用 |
| --- | --- | --- |
| `035a76f` | Redis-backed session support | Redis 承载登录 Session |
| `bb8f54b` | Redis chat memory infrastructure | 模型记忆存储基础设施 |
| `b974953` | Restore chat history into memory | 从业务历史恢复模型上下文 |
| `a7b439b` | App-scoped AI services | 按应用构建和缓存独立 AI 服务 |
| `c237d5c` | Vue 工程项目生成 | 独立流式模型、Vue 生成方法及写文件工具 |
| `0c2bad5` | 工具调用流式输出 | LangChain4j 定制、消息格式、TokenStream 适配与流处理器 |

2026-10-16 新增 Vue 构建器及生成完成/部署接入，前端补齐 Vue 类型和 `dist` 预览地址；修复同目录重叠安装风险，并补充构建器回归与故障排查文档。

2026-10-19 新增需求智能路由、创建逻辑下沉到服务层、源码 ZIP 下载、部署后截图上传 COS 和封面展示兼容。排查真实路由测试时，确认 WebDriverManager 间接引入的 Brotli 0.1.2 与 Spring 响应读取组合会截断正文；普通聊天模型指定 gzip 后原测试通过。完整证据见 [AI 路由故障排查](ai-routing-troubleshooting.md)。

2026-10-21 参照 yu 项目同步 LangGraph4j `1.6.0-rc2`、DashScope SDK `2.21.1` 和独立五节点工作流，修复重复图片调用、固定应用 ID、构建失败误报成功、提示词工具名和日志绑定冲突。2026-10-22 补齐交付配置说明：真实 Pexels 搜图需要 `PEXELS_API_KEY`，Logo 生成需要 `DASHSCOPE_API_KEY`，架构图需要 Mermaid CLI 和 COS；缺少 Key 返回空列表只代表功能降级。配置和验证边界见 [LangGraph 同步与验证](langgraph-sync-verification.md)。

## 3. 关键设计决策与复盘

**应用是上下文和产物的组织单位。** AI 服务缓存键使用应用 ID 和生成类型；模型记忆和生成目录按应用隔离，便于持续修改。仍需补齐应用级并发控制以及删除后的缓存/记忆清理。

**保留业务历史，并从历史恢复模型记忆。** MySQL 负责长期记录，Redis 负责有限窗口。恢复过程当前依赖跳过最新记录，需改为显式识别当前消息，避免并发或其他调用入口导致错位。

**用生成门面协调不同策略。** HTML/多文件通过解析器和保存模板处理，Vue 通过工具逐文件写入；统一流处理后，业务服务无需负责工具消息细节。新增模式仍需多处同步，下一步应完善模式注册和验证流程。

**统一前端文本流。** 工具事件在后端转换成说明和代码块，兼容已有 Markdown 展示。代价是前端缺少结构化工具状态；若引入任务进度与文件树，可增加带版本的事件协议。

**定制依赖实现工具增量输出。** 同包同名 LangChain4j 源码扩展了部分工具回调，但增加了升级维护成本。后续应固定兼容矩阵、补充回归验证，并评估独立补丁或上游扩展点。

**新增依赖可能改变已有客户端行为。** 截图依赖引入 Apache HTTP 和 Brotli 解码后，原有同步模型调用出现 HTTP 200 但正文只剩 `{` 的故障。通过相同输入下对比客户端、压缩设置和读取方式，再检查实际依赖实现，定位到解压与预读的组合问题；使用 gzip 规避后回到原测试确认结果。依赖升级应覆盖这条跨模块回归。

## 4. 验证记录

### 2026-10-21：LangGraph 工作流同步

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| Maven `verify`、Spring Boot 启动和 JAR 打包 | 48 项通过，0 失败/错误/跳过，`BUILD SUCCESS` | Java 21 + Maven 3.9.11；包含原有编辑、SSE、构建和解析器回归 |
| 四个 LangGraph 测试类 | 24 项通过，包含在上述 48 项中 | 图片收集 2、生成图片工具 4、图片请求工具 6、工作流 12 |
| 真实模型、Pexels、unDraw、DashScope、COS、Mermaid CLI 联调 | 本轮未执行 | 模型/云服务使用 Mock 或本地 HTTP，Vue 构建调度也使用替代实现；不能据此声称真实图片服务已可用 |

2026-10-22 补齐启动前配置和验证边界，将本机直接填写的 DashScope Key 保留到 Git 忽略配置，并在提交前重新执行同一套 Maven `verify`：48 项通过，失败/错误/跳过均为 0，Spring Boot 启动和 JAR 打包通过。文档 125 个本地链接检查通过；复验日志位于 `tmp/langgraph-commit-verify.log`。复现命令和功能配置见 [LangGraph 同步与验证](langgraph-sync-verification.md)。

### 2026-10-20：可视化修改与真实环境故障修复

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| 后端编译与定向回归 | 21 项通过，0 失败/错误/跳过 | 文件工具 6、流处理 3、原生保存 2、构建器 6、SSE 错误协议 4 |
| 平台前端类型检查与生产打包 | 通过 | 修复用户删除请求的 ID 类型；保留现有大包体积警告 |
| 隔离后端 + 无头 Edge | 5 组通过 | 三种生成模式的文本/样式修改，以及非所有者与模型失败场景 |
| 真实 DeepSeek + MySQL/Redis + 无头 Edge | 博客副本编辑通过 | 工具局部替换、npm 构建和新预览均成功；原应用文件校验未改变，临时数据库记录已清理 |
| 真实环境网络故障 | 已定位并恢复 | 受限环境启动的后端连接模型报 `Permission denied: getsockopt`；在具备网络权限的环境重启后通过真实调用 |

控制器与前端统一 `generation_error` / `done` 事件，失败结束加载并显示受控提示。最初的模拟回归无法证明模型外网可用；后续对实际运行进程追加了真实模型验收。详细范围、日志位置和复现命令见 [可视化修改记录](visual-editing-verification.md)。

### 4.0 2026-10-19：AI 路由与提交前检查

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| 原路由测试联网复现 | 修复前首次调用失败；修复后 1 项通过、0 失败/错误 | 三条需求实际返回 `html`、`multi_file`、`vue_project`；测试仅打印结果，没有分类断言 |
| 客户端、压缩与读取方式对照 | Apache 默认读取只剩 `{`；JDK、identity、禁用压缩、缓冲区循环读取和 gzip 均取得完整响应 | 已确认解压前响应头为 `Content-Encoding: br`，并检查本地 Brotli 0.1.2 实现 |
| 后端编译及 `VueProjectBuilderTest` | 6 项通过、0 失败/错误 | Java 21 + Maven 3.9.11；首次在沙箱清理系统临时目录时权限失败，指定工作区 `java.io.tmpdir` 后通过 |
| 平台前端 `npm run build` | 未通过；Vite 打包成功 | 仍为已有 `UserManagePage.vue:139` 的 TS2322，`string` 不能赋值给 `number`；补齐下载图标导入，主 JS 约 2.68 MB |
| README/docs 本地链接与差异格式 | 109 个本地链接有效，差异无空白错误 | 包括新增排查文档和源码入口链接 |
| 下载、截图、COS 上传和完整应用创建流程 | 本轮未执行端到端验证 | 以存在对应源码记录功能状态；不运行全量测试，避免额外模型调用、外部站点访问及上传副作用 |

本机 Maven 默认缓存路径和仓库来源标识不匹配，本次使用临时设置匹配现有缓存；该设置及诊断日志保存在被忽略的 `tmp/`，不随源码提交。路由测试命令和配置边界见 [排查文档](ai-routing-troubleshooting.md)。

### 4.1 2026-10-16：Vue 构建与部署

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| `JAVA_HOME` 指向 Java 21，使用 Maven Wrapper 执行 `VueProjectBuilderTest` | 6 项通过，0 失败/错误 | 不连接数据库、Redis 或模型；覆盖并发共享、不同项目并行、重试、输出、失败日志和超时 |
| 平台前端 `npm run build`（提交前复核） | 未通过；Vite 打包成功 | 仍为 `UserManagePage.vue:139` 的 TS2322：`string` 不能赋值给 `number`；主 JS 约 2.68 MB 并有体积警告，本次未修改该页面 |
| 文档相对链接及差异格式 | 通过 | 检查 README/docs 共 89 个本地链接；差异无空白错误 |
| 原故障项目依赖恢复 | `npm ci` 后 `npm run build` 成功 | 已备份损坏的依赖目录；原 Vue 包缺少 `index.js`，恢复后源码无需修改 |
| 修复后的 Java 构建器实际并发执行 | 两个请求成功，只执行一轮安装和打包 | 另一个请求等待并复用结果；验证了 Windows npm.cmd 启动及构建产物 |
| 浏览器真实模型生成与预览 | 单个现代个人博客案例通过 | 使用指定提示词完成生成，iframe 预览正常；分类筛选及文章详情可用 |
| 本地部署站点 | 启动已有 Nginx 后可访问 | 复制部署成功与静态托管可访问分开确认；运行中的后端仍需重启以加载构建修复 |
| 200 行提示词约束 | 未满足 | 生成项目 `src` 目录约 926 行，目前无服务端行数校验 |

页面验收使用当时运行的后端；修复后的构建器通过独立实际并发调用验证。故障原因以并发安装造成依赖损坏最为吻合，但旧实现未保存 npm 诊断，不能完全重建首次安装错误。详见 [Vue 构建与故障排查](vue-build-troubleshooting.md)。

### 4.2 2026-10-14：历史文档整理检查

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| Git 工作区及远程状态 | 整理前工作区干净；成功 fetch；本地领先远程 6 个提交 | 远程为 `https://github.com/qiuquanhui/hui-ai-code-mother.git`，分支 `master` |
| `npm run build` | 未通过 | `UserManagePage.vue:139` 报 TS2322：`string` 不能赋值给 `number`；Vite 打包阶段成功，但整体命令因类型检查退出失败 |
| 前端产物体积 | 存在警告 | 主 JS 约 2.68 MB，超过 Vite 500 kB 警告阈值，需考虑路由懒加载或拆包 |
| `java -version` | 未成功 | PATH 中的 Java 可执行文件引用不存在的 JDK 8 配置；`JAVA_HOME` 指向 JDK 8，项目要求 Java 21 |
| `.\mvnw.cmd -DskipTests compile` | 未完成编译 | Maven 无法创建配置的 `C:\.m2\repository`，在依赖准备阶段失败；本次不能据此判断后端编译通过或失败 |
| AI / 数据库 / Redis / 浏览器端到端验证 | 未执行 | 当前测试使用 SpringBootTest，部分会调用真实模型；本次为文档整理和提交推送，没有生成真实应用 |

以上表格保留当时的检查结果；2026-10-16 已通过显式 Java 21 和项目 Maven Wrapper 完成后端编译及构建器测试。前端完整构建状态需单独检查，不以生成博客的 Vite 构建替代平台前端验收。

## 5. 现存问题与影响

| 优先级 | 问题 | 影响与建议 |
| --- | --- | --- |
| P0 | 静态接口缺少严格路径边界与权限策略 | 文件工具已限制应用源码目录；仍需明确静态资源公开范围与访问控制 |
| P0 | Vue 已本机构建，但缺少构建隔离、版本一致性和原子发布 | 同进程构建合并不能保护跨进程或构建中源码改写，需隔离工作目录及发布版本 |
| P1 | 默认 Java/Maven 环境不匹配，受限启动环境可能阻止模型联网 | 当前前后端构建已通过；使用 Java 21 + Wrapper，并单独验证实际后端进程的模型网络访问 |
| P1 | 缺少完整任务状态、服务端取消与恢复机制 | 已明确区分 `generation_error` 和 `done`，前端卸载主动清理；仍需后端任务取消与版本恢复 |
| P1 | 工具错误仍依赖模型处理，缺少完整任务状态与回滚 | 原生保存与 Vue 构建失败已传播至流；仍需隔离并发生成、验证工具结果并支持撤销 |
| P1 | 相同时间戳下仅按时间游标翻页 | 消息可能遗漏；采用时间和 ID 的复合游标 |
| P1 | 删除只尽力处理数据库历史，无完整资源回收 | 遗留文件、部署站点和记忆；设计补偿与清理任务 |
| P1 | 固定盐 MD5、宽泛跨域、模型请求/响应日志 | 生产使用前需改进认证配置、来源限制与敏感信息日志策略 |
| P1 | 创建应用同步依赖 AI 路由，无分类失败兜底 | 模型异常会阻断创建；需补超时体验、失败策略和分类断言 |
| P1 | 截图共享静态浏览器，异步任务缺少状态、重试与可靠销毁 | 并发截图可能相互影响；应隔离驱动并纳入资源生命周期管理 |
| P1 | 下载过滤只覆盖指定名称/后缀，无符号链接专门处理 | `.env.local` 等文件未覆盖；需完善目录边界和敏感配置排除验收 |
| P2 | 应用列表 VO 封装中仍调用逐条用户查询 | 批量查询结果随后覆盖，仍可能出现冗余 N+1；拆分基础 VO 转换 |
| P2 | SQL 建表非完全幂等、依赖定制缺少专门回归 | 环境迁移与升级不稳定；增量迁移和兼容测试 |

## 6. 下一阶段计划

1. 将已验证的 Java 21 + Maven Wrapper、前端类型检查和无头回归纳入持续集成，并维护真实模型的独立验收。
2. 完成文件边界与产物权限控制，保护应用资源隔离。
3. 在已验证的 Vue 博客案例上完善构建隔离、任务状态、版本化部署及更多资源路径验收。
4. 完善失败、断连、取消、并发和版本生命周期；补齐核心链路的隔离测试。
5. 完善已接入的智能路由评估与兜底、源码下载验收和截图任务管理，再推进页面元素编辑、成本配额和运营指标。

验收用例见 [需求文档](requirements.md)，实现细节及部署约束见 [方案设计](solution-design.md)。

## 2026-10-20 可视化修改

已同步参考项目第 9 期的元素选择、三种模式提示词和五类文件工具，并补充应用目录限制、唯一替换、真实工具结果、构建后完成及预览刷新。实现与本次自动验证边界见 [可视化修改记录](visual-editing-verification.md)。
