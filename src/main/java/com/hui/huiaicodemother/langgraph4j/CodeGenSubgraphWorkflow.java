package com.hui.huiaicodemother.langgraph4j;

import com.hui.huiaicodemother.exception.BusinessException;
import com.hui.huiaicodemother.exception.ErrorCode;
import com.hui.huiaicodemother.langgraph4j.node.concurrent.*;
import com.hui.huiaicodemother.langgraph4j.state.WorkflowContext;
import java.util.Map;
import java.util.function.BiConsumer;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;
import org.springframework.stereotype.Service;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.StateGraph.END;

@Service
public class CodeGenSubgraphWorkflow extends CodeGenWorkflow {
    private StateGraph<MessagesState<String>> subgraph(String name, AsyncNodeAction<MessagesState<String>> action)
            throws GraphStateException {
        return new MessagesStateGraph<String>().addNode(name, action)
                .addEdge(START, name).addEdge(name, END);
    }

    @Override
    public CompiledGraph<MessagesState<String>> createWorkflow() {
        try {
            var graph = commonGraph()
                    .addNode("image_plan", ImagePlanNode.create())
                    .addNode("content_image_subgraph", subgraph("content_collect", ContentImageCollectorNode.create()))
                    .addNode("illustration_subgraph", subgraph("illustration_collect", IllustrationCollectorNode.create()))
                    .addNode("diagram_subgraph", subgraph("diagram_generate", DiagramCollectorNode.create()))
                    .addNode("logo_subgraph", subgraph("logo_generate", LogoCollectorNode.create()))
                    .addNode("image_aggregator", ImageAggregatorNode.create())
                    .addEdge(START, "initialize").addEdge("initialize", "image_plan");
            for (String branch : new String[]{"content_image_subgraph", "illustration_subgraph", "diagram_subgraph", "logo_subgraph"}) {
                graph.addEdge("image_plan", branch).addEdge(branch, "image_aggregator");
            }
            graph.addEdge("image_aggregator", "prompt_enhancer");
            return finishGraph(graph);
        } catch (GraphStateException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "子图工作流创建失败");
        }
    }

    @Override
    protected WorkflowContext execute(WorkflowContext context, BiConsumer<String, Map<String, Object>> listener) {
        return executeWithParallelImages(context, listener);
    }
}
