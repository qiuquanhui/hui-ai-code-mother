package com.hui.huiaicodemother.langgraph4j;

import com.hui.huiaicodemother.exception.BusinessException;
import com.hui.huiaicodemother.exception.ErrorCode;
import com.hui.huiaicodemother.langgraph4j.node.concurrent.*;
import com.hui.huiaicodemother.langgraph4j.state.WorkflowContext;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.function.BiConsumer;
import static org.bsc.langgraph4j.StateGraph.START;

@Service
public class CodeGenConcurrentWorkflow extends CodeGenWorkflow {
    @Override
    public CompiledGraph<MessagesState<String>> createWorkflow() {
        try {
            var graph = commonGraph()
                    .addNode("image_plan", ImagePlanNode.create())
                    .addNode("content_image_collector", ContentImageCollectorNode.create())
                    .addNode("illustration_collector", IllustrationCollectorNode.create())
                    .addNode("diagram_collector", DiagramCollectorNode.create())
                    .addNode("logo_collector", LogoCollectorNode.create())
                    .addNode("image_aggregator", ImageAggregatorNode.create())
                    .addEdge(START, "initialize").addEdge("initialize", "image_plan");
            for (String branch : new String[]{"content_image_collector", "illustration_collector", "diagram_collector", "logo_collector"}) {
                graph.addEdge("image_plan", branch).addEdge(branch, "image_aggregator");
            }
            graph.addEdge("image_aggregator", "prompt_enhancer");
            return finishGraph(graph);
        } catch (GraphStateException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "并发工作流创建失败");
        }
    }

    @Override
    protected WorkflowContext execute(WorkflowContext context, BiConsumer<String, Map<String, Object>> listener) {
        return executeWithParallelImages(context, listener);
    }
}
