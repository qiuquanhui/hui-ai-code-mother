package com.hui.huiaicodemother.langgraph4j;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hui.huiaicodemother.exception.BusinessException;
import com.hui.huiaicodemother.exception.ErrorCode;
import com.hui.huiaicodemother.langgraph4j.node.*;
import com.hui.huiaicodemother.langgraph4j.state.WorkflowContext;
import com.hui.huiaicodemother.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** Image collection, generation, bounded quality repair and optional Vue build. */
@Slf4j
@Service
@Primary
public class CodeGenWorkflow {
    @Value("${langgraph.workflow.max-generation-attempts:3}")
    private int maxGenerationAttempts = 3;

    public CompiledGraph<MessagesState<String>> createWorkflow() {
        try {
            var graph = commonGraph()
                    .addNode("image_collector", ImageCollectorNode.create())
                    .addEdge(START, "initialize")
                    .addEdge("initialize", "image_collector")
                    .addEdge("image_collector", "prompt_enhancer");
            return finishGraph(graph);
        } catch (GraphStateException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工作流创建失败");
        }
    }

    protected StateGraph<MessagesState<String>> commonGraph() throws GraphStateException {
        return new MessagesStateGraph<String>()
                .addNode("initialize", node_async(state -> {
                    WorkflowContext context = WorkflowContext.getContext(state);
                    if (context == null) {
                        Object prompt = state.data().get("originalPrompt");
                        context = initialContext(prompt instanceof String text ? text : null, IdUtil.getSnowflakeNextId());
                    }
                    return WorkflowContext.saveContext(context);
                }))
                .addNode("prompt_enhancer", PromptEnhancerNode.create())
                .addNode("router", RouterNode.create())
                .addNode("code_generator", CodeGeneratorNode.create())
                .addNode("code_quality_check", CodeQualityCheckNode.create())
                .addNode("project_builder", ProjectBuilderNode.create());
    }

    protected CompiledGraph<MessagesState<String>> finishGraph(StateGraph<MessagesState<String>> graph)
            throws GraphStateException {
        var compiled = graph.addEdge("prompt_enhancer", "router")
                .addEdge("router", "code_generator")
                .addEdge("code_generator", "code_quality_check")
                .addConditionalEdges("code_quality_check", edge_async(this::routeAfterQualityCheck),
                        Map.of("build", "project_builder", "skip_build", END, "fail", "code_generator"))
                .addEdge("project_builder", END).compile();
        compiled.setMaxIterations(100);
        return compiled;
    }

    protected String routeAfterQualityCheck(MessagesState<String> state) {
        WorkflowContext context = WorkflowContext.getContext(state);
        var quality = context.getQualityResult();
        if (quality == null || !Boolean.TRUE.equals(quality.getIsValid())) {
            if (context.getGenerationAttempts() >= context.getMaxGenerationAttempts()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "代码质检未通过，已达到最大生成次数");
            }
            return "fail";
        }
        return context.getGenerationType() == CodeGenTypeEnum.VUE_PROJECT ? "build" : "skip_build";
    }

    protected WorkflowContext initialContext(String prompt, Long appId) {
        if (StrUtil.isBlank(prompt) || appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "提示词不能为空，应用 ID 必须为正数");
        }
        if (maxGenerationAttempts < 1 || maxGenerationAttempts > 10) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "最大生成次数必须在 1 到 10 之间");
        }
        return WorkflowContext.builder().appId(appId).originalPrompt(prompt).currentStep("初始化")
                .maxGenerationAttempts(maxGenerationAttempts).build();
    }

    public WorkflowContext executeWorkflow(String prompt) {
        return executeWorkflow(prompt, IdUtil.getSnowflakeNextId());
    }

    public WorkflowContext executeWorkflow(String prompt, Long appId) {
        return execute(initialContext(prompt, appId), (event, data) -> {});
    }

    protected WorkflowContext execute(WorkflowContext context, BiConsumer<String, Map<String, Object>> listener) {
        return runGraph(createWorkflow(), context, RunnableConfig.builder().build(), listener);
    }

    protected WorkflowContext executeWithParallelImages(WorkflowContext context,
                                                        BiConsumer<String, Map<String, Object>> listener) {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            return runGraph(createWorkflow(), context, RunnableConfig.builder()
                    .addParallelNodeExecutor("image_plan", pool).build(), listener);
        }
    }

    protected WorkflowContext runGraph(CompiledGraph<MessagesState<String>> graph, WorkflowContext context,
                                       RunnableConfig config, BiConsumer<String, Map<String, Object>> listener) {
        WorkflowContext result = null;
        int number = 0;
        for (var step : graph.stream(WorkflowContext.saveContext(context), config)) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("工作流已取消");
            WorkflowContext current = WorkflowContext.getContext(step.state());
            if (current == null) continue;
            result = current;
            if (!START.equals(step.node()) && !END.equals(step.node())) {
                listener.accept("step_completed", Map.of("stepNumber", ++number, "node", step.node(),
                        "currentStep", current.getCurrentStep()));
            }
        }
        if (result == null || result.getBuildResultDir() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工作流未产出可用结果");
        }
        return result;
    }

    /** Typed events prevent Spring MVC from wrapping already-framed SSE text a second time. */
    public Flux<ServerSentEvent<Map<String, Object>>> executeWorkflowEvents(String prompt) {
        initialContext(prompt, 1L);
        return Flux.defer(() -> executeWorkflowEvents(prompt, IdUtil.getSnowflakeNextId()));
    }

    public Flux<ServerSentEvent<Map<String, Object>>> executeWorkflowEvents(String prompt, Long appId) {
        initialContext(prompt, appId);
        return Flux.create(sink -> {
            Thread worker = Thread.ofVirtual().name("langgraph-" + appId).unstarted(() -> {
                try {
                    if (sink.isCancelled()) return;
                    sink.next(event("workflow_start", Map.of("message", "开始执行代码生成工作流", "appId", appId)));
                    if (sink.isCancelled()) return;
                    WorkflowContext result = execute(initialContext(prompt, appId), (type, data) -> {
                        if (sink.isCancelled()) throw new CancellationException("工作流已取消");
                        sink.next(event(type, data));
                    });
                    if (!sink.isCancelled()) {
                        sink.next(event("workflow_completed", Map.of("message", "代码生成工作流执行完成", "appId", appId,
                                "generationType", result.getGenerationType().getValue(),
                                "buildResultDir", result.getBuildResultDir())));
                        sink.complete();
                    }
                } catch (Exception e) {
                    if (sink.isCancelled()) return;
                    log.error("工作流执行失败", e);
                    sink.next(event("workflow_error", Map.of("message", "工作流执行失败", "error", "生成或质检失败，请检查服务端日志")));
                    sink.complete();
                }
            });
            sink.onCancel(worker::interrupt);
            worker.start();
        });
    }

    public Flux<String> executeWorkflowWithFlux(String prompt) {
        return executeWorkflowEvents(prompt).map(this::formatEvent);
    }

    public Flux<String> executeWorkflowWithFlux(String prompt, Long appId) {
        return executeWorkflowEvents(prompt, appId).map(this::formatEvent);
    }

    private String formatEvent(ServerSentEvent<Map<String, Object>> event) {
        return "event: " + event.event() + "\ndata: " + JSONUtil.toJsonStr(event.data()) + "\n\n";
    }

    public SseEmitter executeWorkflowWithSse(String prompt) {
        return executeWorkflowWithSse(prompt, IdUtil.getSnowflakeNextId());
    }

    public SseEmitter executeWorkflowWithSse(String prompt, Long appId) {
        Flux<ServerSentEvent<Map<String, Object>>> events = executeWorkflowEvents(prompt, appId);
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        Disposable.Swap subscription = Disposables.swap();
        Runnable cancel = subscription::dispose;
        emitter.onCompletion(cancel);
        emitter.onTimeout(() -> { cancel.run(); emitter.complete(); });
        emitter.onError(error -> cancel.run());
        Disposable running = events.subscribe(event -> {
            try {
                emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
            } catch (Exception e) {
                cancel.run();
                emitter.completeWithError(e);
            }
        }, emitter::completeWithError, emitter::complete);
        subscription.update(running);
        return emitter;
    }

    private static ServerSentEvent<Map<String, Object>> event(String name, Map<String, Object> data) {
        return ServerSentEvent.<Map<String, Object>>builder(data).event(name).build();
    }
}
