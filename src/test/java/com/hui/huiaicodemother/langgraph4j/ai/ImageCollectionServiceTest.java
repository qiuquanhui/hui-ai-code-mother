package com.hui.huiaicodemother.langgraph4j.ai;

import com.hui.huiaicodemother.langgraph4j.tools.*;
import com.hui.huiaicodemother.manager.CosManager;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageCollectionServiceTest {
    @Test
    void springBootUsesLogbackWithoutACompetingSdkLogger() {
        assertInstanceOf(ch.qos.logback.classic.LoggerContext.class, org.slf4j.LoggerFactory.getILoggerFactory());
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.slf4j.simple.SimpleLoggerFactory"));
    }

    @Test
    void serviceAndAllToolsStartWithoutOptionalProviderCredentials() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        try (var application = new AnnotationConfigApplicationContext()) {
            application.registerBean(PropertySourcesPlaceholderConfigurer.class);
            application.getBeanFactory().registerSingleton("chatModel", chatModel);
            application.getBeanFactory().registerSingleton("cosManager", mock(CosManager.class));
            application.register(ImageCollectionServiceFactory.class, CodeQualityCheckServiceFactory.class,
                    ImageCollectionPlanServiceFactory.class, ImageSearchTool.class,
                    LogoGeneratorTool.class, MermaidDiagramTool.class, UndrawIllustrationTool.class);
            application.refresh();
            assertNotNull(application.getBean(ImageCollectionService.class));
            assertNotNull(application.getBean(CodeQualityCheckService.class));
            assertNotNull(application.getBean(ImageCollectionPlanService.class));
            assertTrue(application.getBean(ImageSearchTool.class).searchContentImages("test").isEmpty());
            assertTrue(application.getBean(LogoGeneratorTool.class).generateLogos("test").isEmpty());
            verifyNoInteractions(chatModel);
        }
        try (var prompt = getClass().getResourceAsStream("/prompt/image-collection-system-prompt.txt")) {
            assertNotNull(prompt);
            assertFalse(new String(prompt.readAllBytes(), StandardCharsets.UTF_8).isBlank());
        }
    }
}
