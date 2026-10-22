package com.hui.huiaicodemother.langgraph4j;

import cn.hutool.json.JSONUtil;
import com.hui.huiaicodemother.controller.WorkflowSseController;
import com.hui.huiaicodemother.exception.GlobalExceptionHandler;
import com.hui.huiaicodemother.model.entity.User;
import com.hui.huiaicodemother.service.UserService;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkflowSseControllerTest extends WorkflowTestSupport {
    private final UserService users = mock(UserService.class);
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void controller() {
        user = new User(); user.setId(1L); user.setUserRole("admin");
        when(users.getLoginUser(any())).thenReturn(user);
        WorkflowSseController controller = new WorkflowSseController();
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "codeGenWorkflow", workflow);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .setAsyncRequestTimeout(10000).build();
    }

    private String stream(String endpoint) throws Exception {
        MvcResult result = mvc.perform(get("/workflow/" + endpoint).param("prompt", "site"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10000);
        return mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"execute-flux", "execute-sse"})
    void realMvcTransportProducesNamedEventsAndValidJsonWithoutDoubleFraming(String endpoint) throws Exception {
        String body = stream(endpoint);
        assertTrue(body.contains("event:workflow_start"));
        assertTrue(body.contains("event:workflow_completed"));
        assertFalse(body.contains("data:event:"));
        assertEquals(1, body.lines().filter(line -> line.equals("event:workflow_completed")).count());
        body.lines().filter(line -> line.startsWith("data:")).forEach(line ->
                assertDoesNotThrow(() -> JSONUtil.parseObj(line.substring(5))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"execute-flux", "execute-sse"})
    void realMvcTransportFlushesErrorEventWithoutASuccessEvent(String endpoint) throws Exception {
        when(ai.generateHtmlCodeStream(anyString())).thenReturn(Flux.error(new IllegalStateException("private-url-and-key")));
        String body = stream(endpoint);
        assertTrue(body.contains("event:workflow_error"));
        assertFalse(body.contains("event:workflow_completed"));
        assertFalse(body.contains("private-url-and-key"));
    }

    @Test
    void synchronousEndpointReturnsACompletedContextForAdmin() throws Exception {
        mvc.perform(post("/workflow/execute").param("prompt", "site"))
                .andExpect(status().isOk()).andExpect(jsonPath("generationType").value("HTML"))
                .andExpect(jsonPath("qualityResult.isValid").value(true))
                .andExpect(jsonPath("buildResultDir").isNotEmpty());
    }

    @Test
    void anonymousAndOrdinaryUsersCannotStartPaidWorkflowCalls() throws Exception {
        when(users.getLoginUser(any())).thenReturn(null);
        mvc.perform(post("/workflow/execute").param("prompt", "site")).andExpect(jsonPath("code").value(40100));
        when(users.getLoginUser(any())).thenReturn(user);
        user.setUserRole("user");
        mvc.perform(get("/workflow/execute-flux").param("prompt", "site")).andExpect(jsonPath("code").value(40101));
        mvc.perform(get("/workflow/execute-sse").param("prompt", "site")).andExpect(jsonPath("code").value(40101));
        verifyNoInteractions(images, ai, quality);
    }

    @Test
    void sseTimeoutCancelsTheSubscriptionAndInterruptsWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        when(planner.planImageCollection(anyString())).thenAnswer(invocation -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt(); stopped.countDown(); throw new CancellationException();
            }
            return new com.hui.huiaicodemother.langgraph4j.model.ImageCollectionPlan();
        });
        MvcResult result = mvc.perform(get("/workflow/execute-sse").param("prompt", "site"))
                .andExpect(request().asyncStarted()).andReturn();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        MockAsyncContext async = (MockAsyncContext) result.getRequest().getAsyncContext();
        for (var listener : async.getListeners()) listener.onTimeout(new AsyncEvent(async));
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        verify(ai, never()).generateHtmlCodeStream(anyString());
    }
}
