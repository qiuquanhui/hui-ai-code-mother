package com.hui.huiaicodemother.langgraph4j.config;

import com.hui.huiaicodemother.langgraph4j.CodeGenWorkflow;
import org.bsc.langgraph4j.studio.springboot.AbstractLangGraphStudioConfig;
import org.bsc.langgraph4j.studio.springboot.LangGraphFlow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/** Opt-in local debugging; registers Studio /init and /stream servlets only when enabled. */
@Configuration
@ConditionalOnProperty(name = "langgraph.studio.enabled", havingValue = "true")
public class LangGraphStudioSampleConfig extends AbstractLangGraphStudioConfig {
    private final LangGraphFlow flow;

    public LangGraphStudioSampleConfig(@Qualifier("codeGenWorkflow") CodeGenWorkflow workflow) {
        flow = LangGraphFlow.builder().title("LangGraph Studio")
                .addInputStringArg("originalPrompt", true)
                .stateGraph(workflow.createWorkflow().stateGraph).build();
    }

    @Override
    public LangGraphFlow getFlow() { return flow; }
}
