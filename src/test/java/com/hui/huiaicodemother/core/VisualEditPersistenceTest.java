package com.hui.huiaicodemother.core;

import com.hui.huiaicodemother.ai.*;
import com.hui.huiaicodemother.constant.AppConstant;
import com.hui.huiaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.nio.file.*;
import java.util.Comparator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisualEditPersistenceTest {
    private final long appId = System.nanoTime();
    private final Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "multi_file_" + appId);
    private final AiCodeGeneratorService ai = mock(AiCodeGeneratorService.class);
    private final AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade();

    @BeforeEach
    void setup() {
        AiCodeGeneratorServiceFactory factory = mock(AiCodeGeneratorServiceFactory.class);
        when(factory.getAiCodeGeneratorService(appId, CodeGenTypeEnum.MULTI_FILE)).thenReturn(ai);
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(root)) try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    @Test
    void fullMultiFileEditCanClearCssAndJsWithoutKeepingOldCode() throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("style.css"), "h1 {color:red}");
        Files.writeString(root.resolve("script.js"), "alert('old')");
        when(ai.generateMultiFileCodeStream(anyString())).thenReturn(Flux.just("```html\n<h1>修改后的标题</h1>\n```\n```css\n\n```\n```javascript\n\n```"));
        facade.generateAndSaveCodeStream("edit", CodeGenTypeEnum.MULTI_FILE, appId).collectList().block();
        assertEquals("<h1>修改后的标题</h1>", Files.readString(root.resolve("index.html")));
        assertEquals("", Files.readString(root.resolve("style.css")));
        assertEquals("", Files.readString(root.resolve("script.js")));
    }

    @Test
    void invalidGeneratedCodePropagatesFailureAndPreservesThePreviousPage() throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("index.html"), "previous page");
        when(ai.generateMultiFileCodeStream(anyString())).thenReturn(Flux.just("```css\nh1{}\n```"));
        assertThrows(RuntimeException.class, () -> facade.generateAndSaveCodeStream("edit", CodeGenTypeEnum.MULTI_FILE, appId).collectList().block());
        assertEquals("previous page", Files.readString(root.resolve("index.html")));
    }
}
