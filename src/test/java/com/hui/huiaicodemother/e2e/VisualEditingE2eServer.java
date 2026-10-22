package com.hui.huiaicodemother.e2e;

import cn.hutool.core.bean.BeanUtil;
import com.hui.huiaicodemother.ai.*;
import com.hui.huiaicodemother.ai.tools.*;
import com.hui.huiaicodemother.common.*;
import com.hui.huiaicodemother.config.JsonConfig;
import com.hui.huiaicodemother.constant.AppConstant;
import com.hui.huiaicodemother.controller.*;
import com.hui.huiaicodemother.core.AiCodeGeneratorFacade;
import com.hui.huiaicodemother.core.builder.VueProjectBuilder;
import com.hui.huiaicodemother.core.handler.*;
import com.hui.huiaicodemother.exception.GlobalExceptionHandler;
import com.hui.huiaicodemother.model.entity.*;
import com.hui.huiaicodemother.model.vo.*;
import com.hui.huiaicodemother.service.*;
import com.hui.huiaicodemother.service.impl.AppServiceImpl;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Local test-only server. No database, network model, Redis or cloud credentials are used. */
public class VisualEditingE2eServer {
    static final Map<Long, App> APPS = new ConcurrentHashMap<>();
    static final Map<Long, List<ChatHistory>> HISTORY = new ConcurrentHashMap<>();
    static final Map<Object, List<ChatMessage>> MEMORY = new ConcurrentHashMap<>();
    public static final long HTML_ID = 991001L, MULTI_ID = 991002L, VUE_ID = 991003L;
    static final String PAGE = "<!doctype html><html><head><meta charset=\"UTF-8\"><title>Visual edit fixture</title></head><body><h1 id=\"title\">Original title</h1><p id=\"untouched\">Keep this content</p><a id=\"route-link\" href=\"#details\">Details</a><div class=\"sm:hover\"><svg id=\"icon\" width=\"80\" height=\"60\"><circle cx=\"30\" cy=\"30\" r=\"25\" /></svg></div></body></html>";

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("visual.e2e")) throw new IllegalStateException("Requires -Dvisual.e2e=true");
        seed();
        SpringApplication app = new SpringApplication(FixtureConfiguration.class);
        app.setAdditionalProfiles("visual-e2e");
        app.run("--spring.config.name=visual-e2e", "--spring.main.allow-bean-definition-overriding=true", "--server.address=127.0.0.1", "--server.port=" + Integer.getInteger("visual.e2e.port", 8123), "--server.servlet.context-path=/api");
    }

    static void seed() throws Exception {
        for (var entry : Map.of(HTML_ID, "html", MULTI_ID, "multi_file", VUE_ID, "vue_project").entrySet()) {
            App app = new App();
            app.setId(entry.getKey()); app.setUserId(1L); app.setAppName("Visual " + entry.getValue());
            app.setCodeGenType(entry.getValue()); APPS.put(app.getId(), app);
            HISTORY.put(app.getId(), new CopyOnWriteArrayList<>());
            addHistory(app.getId(), "Build a fixture", "user", 1L);
            addHistory(app.getId(), "Fixture ready", "ai", 1L);
            Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, entry.getValue() + "_" + app.getId());
            // Fixed IDs are reserved for this test. Refuse to overwrite existing directories.
            if (Files.exists(root) && !Files.exists(root.resolve(".visual-edit-fixture"))) throw new IllegalStateException("Existing non-fixture directory: " + root);
            Files.createDirectories(root);
            Files.writeString(root.resolve(".visual-edit-fixture"), "Owned by VisualEditingE2eServer");
            if (app.getId() == VUE_ID) {
                new FileWriteTool().writeFile("package.json", "{\"name\":\"visual-edit-fixture\",\"private\":true,\"type\":\"module\",\"scripts\":{\"build\":\"node ../../../hui-ai-code-mother-frontend/node_modules/vite/bin/vite.js build\"},\"dependencies\":{\"vue\":\"file:../../../hui-ai-code-mother-frontend/node_modules/vue\"}}", app.getId());
                new FileWriteTool().writeFile("index.html", "<!doctype html><html><head><meta charset=\"UTF-8\"></head><body><div id=\"app\"></div><script type=\"module\" src=\"/src/main.js\"></script></body></html>", app.getId());
                new FileWriteTool().writeFile("vite.config.mjs", "import vue from '../../../hui-ai-code-mother-frontend/node_modules/@vitejs/plugin-vue/dist/index.mjs'; export default {base:'./',plugins:[vue()]};", app.getId());
                new FileWriteTool().writeFile("src/main.js", "import {createApp} from 'vue'; import App from './App.vue'; createApp(App).mount('#app');", app.getId());
                new FileWriteTool().writeFile("src/App.vue", "<template><h1 id=\"title\">Original title</h1><p id=\"untouched\">Keep this content</p><a id=\"route-link\" href=\"#details\">Details</a><div class=\"sm:hover\"><svg id=\"icon\" width=\"80\" height=\"60\"><circle cx=\"30\" cy=\"30\" r=\"25\" /></svg></div></template>", app.getId());
                if (!new VueProjectBuilder().buildProject(root.toString())) throw new IllegalStateException("Fixture build failed");
            } else {
                Files.writeString(root.resolve("index.html"), page(app.getId() == MULTI_ID));
                if (app.getId() == MULTI_ID) {
                    Files.writeString(root.resolve("style.css"), "h1 { color: navy; }");
                    Files.writeString(root.resolve("script.js"), "window.fixtureReady = true;");
                }
            }
        }
    }

    static String page(boolean multi) {
        return multi ? PAGE.replace("</head>", "<link rel=\"stylesheet\" href=\"style.css\"></head>")
                .replace("</body>", "<script src=\"script.js\"></script></body>") : PAGE;
    }

    static boolean addHistory(long appId, String text, String type, long userId) {
        ChatHistory history = new ChatHistory();
        history.setAppId(appId); history.setMessage(text); history.setMessageType(type);
        history.setUserId(userId); history.setCreateTime(LocalDateTime.now());
        HISTORY.get(appId).add(history); return true;
    }

    static User user(HttpServletRequest request) {
        boolean viewer = request.getHeader("Cookie") != null && request.getHeader("Cookie").contains("e2eViewer=1");
        User user = new User(); user.setId(viewer ? 2L : 1L); user.setUserName(viewer ? "Viewer" : "Owner"); user.setUserRole("user"); return user;
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("visual-e2e")
    @Import({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            JsonConfig.class, AppController.class, StaticResourceController.class, GlobalExceptionHandler.class,
            AiCodeGeneratorFacade.class, AiCodeGeneratorServiceFactory.class, VueProjectBuilder.class,
            JsonMessageStreamHandler.class, StreamHandlerExecutor.class, ToolManager.class,
            FileWriteTool.class, FileReadTool.class, FileDirReadTool.class, FileModifyTool.class, FileDeleteTool.class,
            FixtureEndpoints.class})
    static class FixtureConfiguration {
        @Bean com.hui.huiaicodemother.mapper.AppMapper appMapper() { return mock(com.hui.huiaicodemother.mapper.AppMapper.class); }
        @Bean UserService userService() {
            UserService service = mock(UserService.class);
            when(service.getLoginUser(any())).thenAnswer(inv -> user(inv.getArgument(0)));
            return service;
        }
        @Bean ProjectDownloadService projectDownloadService() { return mock(ProjectDownloadService.class); }
        @Bean ScreenshotService screenshotService() { return mock(ScreenshotService.class); }
        @Bean AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService() { return mock(AiCodeGenTypeRoutingService.class); }
        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean StreamingChatModel openAiStreamingChatModel() { return new FixtureModel(); }
        @Bean StreamingChatModel reasoningStreamingChatModel() { return new FixtureModel(); }
        @Bean RedisChatMemoryStore redisChatMemoryStore() {
            RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
            when(store.getMessages(any())).thenAnswer(inv -> new ArrayList<>(MEMORY.getOrDefault(inv.getArgument(0), List.of())));
            doAnswer(inv -> { MEMORY.put(inv.getArgument(0), new ArrayList<>(inv.getArgument(1))); return null; }).when(store).updateMessages(any(), anyList());
            return store;
        }
        @Bean ChatHistoryService chatHistoryService() {
            ChatHistoryService service = mock(ChatHistoryService.class);
            when(service.addChatMessage(anyLong(), anyString(), anyString(), anyLong())).thenAnswer(inv -> addHistory(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
            return service;
        }
        @Bean AppService appService() {
            AppServiceImpl service = spy(new AppServiceImpl());
            doAnswer(inv -> APPS.get(Long.parseLong(inv.getArgument(0).toString()))).when(service).getById(any(Serializable.class));
            return service;
        }
    }

    @RestController
    @Profile("visual-e2e")
    static class FixtureEndpoints {
        @GetMapping("/user/get/login") BaseResponse<LoginUserVO> login(HttpServletRequest request) {
            return ResultUtils.success(BeanUtil.copyProperties(user(request), LoginUserVO.class));
        }
        @GetMapping("/chatHistory/app/{appId}") BaseResponse<Map<String, Object>> history(@PathVariable long appId) {
            List<ChatHistory> records = new ArrayList<>(HISTORY.get(appId));
            Collections.reverse(records);
            return ResultUtils.success(Map.of("records", records.subList(0, Math.min(10, records.size()))));
        }
        @GetMapping("/e2e/health") Map<String, Object> health() { return Map.of("ready", true); }
    }

    /** Deterministic model exercises real AiServices tool dispatch and the production stream handlers. */
    static class FixtureModel implements StreamingChatModel {
        @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            try {
                int lastUser = -1;
                for (int i = 0; i < request.messages().size(); i++) if (request.messages().get(i) instanceof UserMessage) lastUser = i;
                String prompt = ((UserMessage) request.messages().get(lastUser)).singleText();
                if (prompt.contains("FAIL_STREAM")) { handler.onError(new IllegalStateException("Fixture model failure")); return; }
                boolean styleEdit = prompt.contains("STYLE_EDIT");
                if (!prompt.contains("选中元素信息") || !prompt.contains("h1#title") || !prompt.contains(styleEdit ? "Edited title" : "Original title")) {
                    throw new IllegalStateException("Element context was not forwarded to the model: " + prompt);
                }
                String system = ((SystemMessage) request.messages().getFirst()).text();
                if (system.contains("Vue3")) {
                    int toolCount = 0;
                    for (int i = lastUser + 1; i < request.messages().size(); i++) if (request.messages().get(i) instanceof ToolExecutionResultMessage) toolCount++;
                    if (toolCount < 3) {
                        String name = new String[]{"readDir", "readFile", "modifyFile"}[toolCount];
                        String args = switch (toolCount) {
                            case 0 -> "{\"relativeDirPath\":\"\"}";
                            case 1 -> "{\"relativeFilePath\":\"src/App.vue\"}";
                            default -> cn.hutool.json.JSONUtil.toJsonStr(Map.of("relativeFilePath", "src/App.vue",
                                    "oldContent", styleEdit ? "<h1 id=\"title\">" : "Original title",
                                    "newContent", styleEdit ? "<h1 id=\"title\" style=\"color: rgb(12, 34, 56)\">" : "Edited title"));
                        };
                        ToolExecutionRequest tool = ToolExecutionRequest.builder().id("tool-" + toolCount).name(name).arguments(args).build();
                        handler.onPartialToolExecutionRequest(0, tool);
                        handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(tool)).build());
                        return;
                    }
                    handler.onPartialResponse("修改完成");
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("修改完成")).build());
                } else {
                    String html = page(system.contains("style.css")).replace("Original title", "Edited title");
                    if (styleEdit && !system.contains("style.css")) html = html.replace("<h1 id=\"title\">", "<h1 id=\"title\" style=\"color: rgb(12, 34, 56)\">");
                    String result = "```html\n" + html + "\n```";
                    if (system.contains("style.css")) result += "\n```css\nh1 { color: " + (styleEdit ? "rgb(12, 34, 56)" : "navy") + "; }\n```\n```javascript\nwindow.fixtureReady = true;\n```";
                    handler.onPartialResponse(result);
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(result)).build());
                }
            } catch (Throwable error) { handler.onError(error); }
        }
    }
}
