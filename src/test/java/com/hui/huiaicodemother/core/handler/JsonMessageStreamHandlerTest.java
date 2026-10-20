package com.hui.huiaicodemother.core.handler;

import cn.hutool.json.JSONUtil;
import com.hui.huiaicodemother.ai.tools.*;
import com.hui.huiaicodemother.core.builder.VueProjectBuilder;
import com.hui.huiaicodemother.model.entity.User;
import com.hui.huiaicodemother.service.ChatHistoryService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JsonMessageStreamHandlerTest {
    private final JsonMessageStreamHandler handler = new JsonMessageStreamHandler();
    private final VueProjectBuilder builder = mock(VueProjectBuilder.class);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final User user = new User();

    @BeforeEach
    void setup() {
        user.setId(1L);
        ToolManager manager = new ToolManager();
        ReflectionTestUtils.setField(manager, "tools", new BaseTool[]{new FileWriteTool(), new FileReadTool(), new FileModifyTool(), new FileDeleteTool(), new FileDirReadTool()});
        manager.initTools();
        ReflectionTestUtils.setField(handler, "toolManager", manager);
        ReflectionTestUtils.setField(handler, "vueProjectBuilder", builder);
        when(builder.buildProject(anyString())).thenReturn(true);
    }

    @Test
    void displaysEachToolOnceAndPersistsActualExecutionResultBeforeCompletion() {
        String request = JSONUtil.toJsonStr(Map.of("type", "tool_request", "id", "t1", "name", "modifyFile"));
        String executed = JSONUtil.toJsonStr(Map.of("type", "tool_executed", "name", "modifyFile", "arguments",
                JSONUtil.toJsonStr(Map.of("relativeFilePath", "src/App.vue", "oldContent", "old", "newContent", "new")),
                "result", "错误：文件不存在"));
        var chunks = handler.handle(Flux.just(request, request, executed), history, 123, user).collectList().block();
        assertEquals(2, chunks.size());
        assertTrue(chunks.getFirst().contains("修改文件"));
        assertTrue(chunks.getLast().contains("错误：文件不存在"));
        var order = inOrder(builder, history);
        order.verify(builder).buildProject(contains("vue_project_123"));
        order.verify(history).addChatMessage(eq(123L), contains("错误：文件不存在"), eq("ai"), eq(1L));
        verify(builder, never()).buildProjectAsync(anyString());
    }

    @Test
    void unknownToolsAndUnknownTypesDoNotCrashTheStream() {
        String request = JSONUtil.toJsonStr(Map.of("type", "tool_request", "id", "t1", "name", "missing"));
        assertEquals(0, handler.handle(Flux.just(request, "{\"type\":\"unknown\"}"), history, 123, user).collectList().block().size());
    }

    @Test
    void buildFailurePropagatesAndRecordsFailureInsteadOfSuccess() {
        when(builder.buildProject(anyString())).thenReturn(false);
        assertThrows(RuntimeException.class, () -> handler.handle(Flux.just("{\"type\":\"ai_response\",\"data\":\"修改完毕\"}"), history, 123, user).collectList().block());
        verify(history).addChatMessage(eq(123L), contains("AI回复失败"), eq("ai"), eq(1L));
        verify(history, never()).addChatMessage(eq(123L), eq("修改完毕"), anyString(), anyLong());
    }
}
