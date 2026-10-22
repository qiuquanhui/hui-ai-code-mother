package com.hui.huiaicodemother.langgraph4j.node;

import com.hui.huiaicodemother.langgraph4j.ai.ImageCollectionPlanService;
import com.hui.huiaicodemother.langgraph4j.model.ImageResource;
import com.hui.huiaicodemother.langgraph4j.state.WorkflowContext;
import com.hui.huiaicodemother.langgraph4j.tools.*;
import com.hui.huiaicodemother.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import java.util.*;
import java.util.concurrent.*;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** The serial graph uses one image node; its planned provider tasks run in parallel. */
@Slf4j
public class ImageCollectorNode {
    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            List<ImageResource> images = new ArrayList<>();
            try {
                var plan = SpringContextUtil.getBean(ImageCollectionPlanService.class)
                        .planImageCollection(context.getOriginalPrompt());
                context.setImageCollectionPlan(plan);
                List<Callable<List<ImageResource>>> tasks = new ArrayList<>();
                if (plan != null) {
                    if (plan.getContentImageTasks() != null) for (var task : plan.getContentImageTasks()) {
                        if (task != null) tasks.add(() -> SpringContextUtil.getBean(ImageSearchTool.class).searchContentImages(task.query()));
                    }
                    if (plan.getIllustrationTasks() != null) for (var task : plan.getIllustrationTasks()) {
                        if (task != null) tasks.add(() -> SpringContextUtil.getBean(UndrawIllustrationTool.class).searchIllustrations(task.query()));
                    }
                    if (plan.getDiagramTasks() != null) for (var task : plan.getDiagramTasks()) {
                        if (task != null) tasks.add(() -> SpringContextUtil.getBean(MermaidDiagramTool.class)
                                .generateMermaidDiagram(task.mermaidCode(), task.description()));
                    }
                    if (plan.getLogoTasks() != null) for (var task : plan.getLogoTasks()) {
                        if (task != null) tasks.add(() -> SpringContextUtil.getBean(LogoGeneratorTool.class).generateLogos(task.description()));
                    }
                }
                if (!tasks.isEmpty()) try (var pool = Executors.newFixedThreadPool(4)) {
                    for (var future : pool.invokeAll(tasks)) {
                        try {
                            var result = future.get();
                            if (result != null) result.stream().filter(Objects::nonNull).forEach(images::add);
                        } catch (ExecutionException e) {
                            log.warn("单个图片任务失败，保留其他图片结果", e.getCause());
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("图片收集已取消");
            } catch (Exception e) {
                log.warn("图片规划或收集失败，继续使用已有素材", e);
            }
            context.setImageList(List.copyOf(images));
            context.setCurrentStep("图片收集");
            return WorkflowContext.saveContext(context);
        });
    }
}
