package com.hui.huiaicodemother.langgraph4j;

import com.hui.huiaicodemother.langgraph4j.config.LangGraphStudioSampleConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class LangGraphStudioConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean("codeGenWorkflow", CodeGenWorkflow.class, CodeGenWorkflow::new)
            .withUserConfiguration(LangGraphStudioSampleConfig.class);

    @Test
    void studioServletsAreDisabledByDefault() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("initServletBean"));
            assertFalse(context.containsBean("streamingServletBean"));
        });
    }

    @Test
    void explicitlyEnabledStudioCompilesAFlowWithAUsablePromptInput() {
        runner.withPropertyValues("langgraph.studio.enabled=true").run(context -> {
            assertNull(context.getStartupFailure());
            var config = context.getBean(LangGraphStudioSampleConfig.class);
            assertTrue(context.containsBean("initServletBean"));
            assertTrue(context.containsBean("streamingServletBean"));
            assertEquals("originalPrompt", config.getFlow().inputArgs().getFirst().name());
            assertDoesNotThrow(() -> config.getFlow().stateGraph().compile());
        });
    }
}
