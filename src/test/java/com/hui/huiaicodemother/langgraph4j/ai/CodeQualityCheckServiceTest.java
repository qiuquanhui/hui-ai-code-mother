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
class CodeQualityCheckServiceTest {
    private ChatModel model(String json) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(json)).build());
        return model;
    }

    @Test
    void qualityServiceParsesTheActualModelJsonContract() {
        var model = model("{\"isValid\":false,\"errors\":[\"missing import\"],\"suggestions\":[\"add import\"]}");
        var factory = new CodeQualityCheckServiceFactory();
        ReflectionTestUtils.setField(factory, "chatModel", model);
        var result = factory.createCodeQualityCheckService().checkCodeQuality("<html>code</html>");
        assertFalse(result.getIsValid());
        assertEquals("missing import", result.getErrors().getFirst());
        assertEquals("add import", result.getSuggestions().getFirst());
        verify(model).chat(any(ChatRequest.class));
    }

    @Test
    void malformedQualityJsonIsAnErrorInsteadOfAPassingResult() {
        var factory = new CodeQualityCheckServiceFactory();
        ReflectionTestUtils.setField(factory, "chatModel", model("not-json"));
        var service = factory.createCodeQualityCheckService();
        assertThrows(RuntimeException.class, () -> service.checkCodeQuality("code"));
    }
}
