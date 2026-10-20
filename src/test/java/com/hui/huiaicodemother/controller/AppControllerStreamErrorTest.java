package com.hui.huiaicodemother.controller;

import cn.hutool.json.JSONUtil;
import com.hui.huiaicodemother.exception.*;
import com.hui.huiaicodemother.model.entity.User;
import com.hui.huiaicodemother.service.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.net.SocketException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppControllerStreamErrorTest {
    private final AppController controller = new AppController();
    private final AppService service = mock(AppService.class);
    private final UserService users = mock(UserService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(controller, "appService", service);
        ReflectionTestUtils.setField(controller, "userService", users);
        User user = new User(); user.setId(1L);
        when(users.getLoginUser(request)).thenReturn(user);
    }

    @Test void synchronousNetworkFailureIsAnSseErrorWithoutDoneOrSensitiveDetails() {
        when(service.chatToGenCode(anyLong(), anyString(), any())).thenThrow(new RuntimeException("private-url-and-key", new SocketException("Permission denied: getsockopt")));
        var events = controller.chatToGenCode(1L, "edit", request).collectList().block();
        assertEquals(1, events.size());
        assertEquals("generation_error", events.getFirst().event());
        String data = events.getFirst().data();
        assertTrue(JSONUtil.parseObj(data).getStr("message").contains("网络权限"));
        assertFalse(data.contains("private-url-and-key"));
    }

    @Test void failureAfterPartialOutputNeverEmitsDone() {
        when(service.chatToGenCode(anyLong(), anyString(), any())).thenReturn(Flux.concat(Flux.just("partial"), Flux.error(new RuntimeException("failed"))));
        var events = controller.chatToGenCode(1L, "edit", request).collectList().block();
        assertEquals(2, events.size());
        assertEquals("partial", JSONUtil.parseObj(events.getFirst().data()).getStr("d"));
        assertEquals("generation_error", events.getLast().event());
        assertTrue(events.stream().noneMatch(e -> "done".equals(e.event())));
    }

    @Test void ownershipFailureIsReportedWithBusinessCode() {
        when(service.chatToGenCode(anyLong(), anyString(), any())).thenThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用"));
        var event = controller.chatToGenCode(1L, "edit", request).blockLast();
        assertEquals("generation_error", event.event());
        assertEquals(40101, JSONUtil.parseObj(event.data()).getInt("code"));
    }

    @Test void successfulStreamStillEndsWithDone() {
        when(service.chatToGenCode(anyLong(), anyString(), any())).thenReturn(Flux.just("ok"));
        var events = controller.chatToGenCode(1L, "edit", request).collectList().block();
        assertEquals("done", events.getLast().event());
        assertEquals(2, events.size());
    }
}
