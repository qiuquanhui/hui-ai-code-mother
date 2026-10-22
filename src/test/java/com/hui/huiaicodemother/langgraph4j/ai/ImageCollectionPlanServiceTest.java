package com.hui.huiaicodemother.langgraph4j.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real prompt loading and structured model-output parsing, with an in-memory model. */
class ImageCollectionPlanServiceTest {
    private ChatModel model(String json) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(json)).build());
        return model;
    }

    @Test
    void imagePlanServiceParsesListsAndRecordTaskParameters() {
        var model = model("{\"contentImageTasks\":[{\"query\":\"forest\"}],\"illustrationTasks\":[],"
                + "\"diagramTasks\":[{\"mermaidCode\":\"graph TD; A-->B\",\"description\":\"system\"}],"
                + "\"logoTasks\":[{\"description\":\"bird\"}]}");
        var factory = new ImageCollectionPlanServiceFactory();
        ReflectionTestUtils.setField(factory, "chatModel", model);
        var result = factory.createImageCollectionPlanService().planImageCollection("site");
        assertEquals("forest", result.getContentImageTasks().getFirst().query());
        assertTrue(result.getIllustrationTasks().isEmpty());
        assertEquals("graph TD; A-->B", result.getDiagramTasks().getFirst().mermaidCode());
        assertEquals("bird", result.getLogoTasks().getFirst().description());
    }
}
