package com.hui.huiaicodemother.controller;

import com.hui.huiaicodemother.exception.BusinessException;
import com.hui.huiaicodemother.exception.ErrorCode;
import com.hui.huiaicodemother.langgraph4j.CodeGenWorkflow;
import com.hui.huiaicodemother.langgraph4j.state.WorkflowContext;
import com.hui.huiaicodemother.model.enums.UserRoleEnum;
import com.hui.huiaicodemother.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import java.util.Map;

/** Administrator-only standalone workflow demos; app chat continues to use its existing endpoints. */
@RestController
@RequestMapping("/workflow")
public class WorkflowSseController {
    @Resource
    private CodeGenWorkflow codeGenWorkflow;
    @Resource
    private UserService userService;

    @PostMapping("/execute")
    public WorkflowContext executeWorkflow(@RequestParam String prompt, HttpServletRequest request) {
        requireAdmin(request);
        return codeGenWorkflow.executeWorkflow(prompt);
    }

    @GetMapping(value = "/execute-flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> executeWorkflowWithFlux(@RequestParam String prompt,
                                                                           HttpServletRequest request) {
        requireAdmin(request);
        return codeGenWorkflow.executeWorkflowEvents(prompt);
    }

    @GetMapping(value = "/execute-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeWorkflowWithSse(@RequestParam String prompt, HttpServletRequest request) {
        requireAdmin(request);
        return codeGenWorkflow.executeWorkflowWithSse(prompt);
    }

    private void requireAdmin(HttpServletRequest request) {
        var user = userService.getLoginUser(request);
        if (user == null) throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        if (!UserRoleEnum.ADMIN.getValue().equals(user.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }
}
