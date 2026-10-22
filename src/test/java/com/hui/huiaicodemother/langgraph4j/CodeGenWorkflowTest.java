package com.hui.huiaicodemother.langgraph4j;

import com.hui.huiaicodemother.ai.*;
import com.hui.huiaicodemother.constant.AppConstant;
import com.hui.huiaicodemother.core.AiCodeGeneratorFacade;
import com.hui.huiaicodemother.core.builder.VueProjectBuilder;
import com.hui.huiaicodemother.exception.BusinessException;
import com.hui.huiaicodemother.langgraph4j.ai.ImageCollectionService;
import com.hui.huiaicodemother.langgraph4j.state.WorkflowContext;
import com.hui.huiaicodemother.model.enums.CodeGenTypeEnum;
import com.hui.huiaicodemother.utils.SpringContextUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs the real compiled graph; only model calls and npm are substituted. */
class CodeGenWorkflowTest {
    private final ImageCollectionService images = mock(ImageCollectionService.class);
    private final AiCodeGenTypeRoutingService router = mock(AiCodeGenTypeRoutingService.class);
    private final VueProjectBuilder builder = mock(VueProjectBuilder.class);
    private final AiCodeGeneratorService ai = mock(AiCodeGeneratorService.class);
    private final AiCodeGeneratorFacade facade = spy(new AiCodeGeneratorFacade());
    private final CodeGenWorkflow workflow = new CodeGenWorkflow();
    private final List<Path> outputs = new ArrayList<>();
    private GenericApplicationContext application;
    private ApplicationContext previousContext;
    private long appId;

    @BeforeEach
    void setup() {
        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringContextUtil.class, "applicationContext");
        appId = Math.abs(System.nanoTime());
        AiCodeGeneratorServiceFactory factory = mock(AiCodeGeneratorServiceFactory.class);
        when(factory.getAiCodeGeneratorService(anyLong(), any())).thenReturn(ai);
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);
        when(images.collectImages(anyString())).thenReturn("https://images.example.test/hero.png");
        when(router.routeCodeGenType(anyString())).thenReturn(CodeGenTypeEnum.HTML);
        when(ai.generateHtmlCodeStream(anyString())).thenReturn(Flux.just("```html\n<h1>generated</h1>\n```"));
        when(ai.generateMultiFileCodeStream(anyString())).thenReturn(Flux.just(
                "```html\n<h1>generated</h1>\n```\n```css\nh1{color:red}\n```\n```javascript\nconsole.log(1)\n```"));
        application = new GenericApplicationContext();
        application.getBeanFactory().registerSingleton("images", images);
        application.getBeanFactory().registerSingleton("router", router);
        application.getBeanFactory().registerSingleton("facade", facade);
        application.getBeanFactory().registerSingleton("builder", builder);
        application.refresh();
        new SpringContextUtil().setApplicationContext(application);
    }

    @AfterEach
    void cleanup() throws Exception {
        new SpringContextUtil().setApplicationContext(previousContext);
        application.close();
        for (Path output : outputs) if (Files.exists(output)) {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private Path output(CodeGenTypeEnum type, long id) {
        Path path = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, type.getValue() + "_" + id);
        outputs.add(path);
        return path;
    }

    private void mockVueGeneration(boolean buildSuccess, boolean produceDist) {
        doAnswer(invocation -> Flux.defer(() -> {
            try {
                Path root = output(CodeGenTypeEnum.VUE_PROJECT, invocation.getArgument(2));
                Files.createDirectories(root);
                Files.writeString(root.resolve("index.html"), "<div id='app'></div>");
                return Flux.just("generated");
            } catch (Exception e) { return Flux.error(e); }
        })).when(facade).generateAndSaveCodeStream(anyString(), eq(CodeGenTypeEnum.VUE_PROJECT), anyLong());
        when(builder.buildProject(anyString())).thenAnswer(invocation -> {
            if (produceDist) {
                Path dist = Path.of(invocation.getArgument(0, String.class), "dist");
                Files.createDirectories(dist);
                Files.writeString(dist.resolve("index.html"), "built page");
            }
            return buildSuccess;
        });
    }

    @ParameterizedTest
    @EnumSource(CodeGenTypeEnum.class)
    void executesAllFiveNodesAndPersistsEachGenerationType(CodeGenTypeEnum type) throws Exception {
        when(router.routeCodeGenType(anyString())).thenReturn(type);
        Path root = output(type, appId);
        if (type == CodeGenTypeEnum.VUE_PROJECT) mockVueGeneration(true, true);
        WorkflowContext result = workflow.executeWorkflow("Build a website", appId);
        assertEquals(appId, result.getAppId());
        assertEquals(type, result.getGenerationType());
        assertEquals("项目构建", result.getCurrentStep());
        assertTrue(result.getEnhancedPrompt().contains("https://images.example.test/hero.png"));
        assertEquals(root, Path.of(result.getGeneratedCodeDir()));
        Path built = type == CodeGenTypeEnum.VUE_PROJECT ? root.resolve("dist") : root;
        assertEquals(built, Path.of(result.getBuildResultDir()));
        assertTrue(Files.isRegularFile(built.resolve("index.html")));
        assertNull(result.getErrorMessage());
        verify(images, times(1)).collectImages("Build a website");
        verify(router).routeCodeGenType("Build a website");
        verify(facade).generateAndSaveCodeStream(result.getEnhancedPrompt(), type, appId);
        if (type == CodeGenTypeEnum.MULTI_FILE) {
            assertEquals("h1{color:red}", Files.readString(root.resolve("style.css")));
            assertEquals("console.log(1)", Files.readString(root.resolve("script.js")));
        }
        if (type != CodeGenTypeEnum.VUE_PROJECT) verifyNoInteractions(builder);
    }

    @Test
    void imageAndRoutingFailuresFallBackToOriginalPromptAndHtml() {
        when(images.collectImages(anyString())).thenThrow(new IllegalStateException("provider unavailable"));
        when(router.routeCodeGenType(anyString())).thenThrow(new IllegalStateException("model unavailable"));
        output(CodeGenTypeEnum.HTML, appId);
        WorkflowContext result = workflow.executeWorkflow("Build a website", appId);
        assertEquals("Build a website", result.getEnhancedPrompt());
        assertEquals(CodeGenTypeEnum.HTML, result.getGenerationType());
        assertTrue(Files.isRegularFile(Path.of(result.getBuildResultDir(), "index.html")));
    }

    @Test
    void nullRoutingResultFallsBackToHtml() {
        when(router.routeCodeGenType(anyString())).thenReturn(null);
        output(CodeGenTypeEnum.HTML, appId);
        assertEquals(CodeGenTypeEnum.HTML, workflow.executeWorkflow("site", appId).getGenerationType());
    }

    @Test
    void generationErrorStopsTheGraphBeforeBuild() {
        when(ai.generateHtmlCodeStream(anyString())).thenReturn(Flux.error(new IllegalStateException("model failed")));
        assertThrows(RuntimeException.class, () -> workflow.executeWorkflow("site", appId));
        verifyNoInteractions(builder);
    }

    @Test
    void invalidGeneratedCodeCannotReportSuccess() {
        when(ai.generateHtmlCodeStream(anyString())).thenReturn(Flux.just(" "));
        output(CodeGenTypeEnum.HTML, appId);
        assertThrows(RuntimeException.class, () -> workflow.executeWorkflow("site", appId));
        verifyNoInteractions(builder);
    }

    @Test
    void vueBuildFailureCannotReturnTheSourceDirectoryAsSuccess() {
        when(router.routeCodeGenType(anyString())).thenReturn(CodeGenTypeEnum.VUE_PROJECT);
        mockVueGeneration(false, false);
        assertThrows(RuntimeException.class, () -> workflow.executeWorkflow("site", appId));
        verify(builder).buildProject(anyString());
    }

    @Test
    void successfulBuilderWithoutDistIsRejected() {
        when(router.routeCodeGenType(anyString())).thenReturn(CodeGenTypeEnum.VUE_PROJECT);
        mockVueGeneration(true, false);
        assertThrows(RuntimeException.class, () -> workflow.executeWorkflow("site", appId));
    }

    @Test
    void missingGeneratedFilesCannotReportSuccess() {
        doReturn(Flux.just("done")).when(facade).generateAndSaveCodeStream(anyString(), any(), anyLong());
        assertThrows(RuntimeException.class, () -> workflow.executeWorkflow("site", appId));
    }

    @Test
    void separateCallsWithoutAnIdUseSeparateOutputDirectories() {
        List<Long> ids = new ArrayList<>();
        doAnswer(invocation -> {
            long id = invocation.getArgument(2);
            ids.add(id);
            Path root = output(CodeGenTypeEnum.HTML, id);
            Files.createDirectories(root);
            Files.writeString(root.resolve("index.html"), "generated");
            return Flux.just("done");
        }).when(facade).generateAndSaveCodeStream(anyString(), any(), anyLong());
        WorkflowContext first = workflow.executeWorkflow("first site");
        WorkflowContext second = workflow.executeWorkflow("second site");
        assertEquals(2, ids.size());
        assertNotEquals(first.getAppId(), second.getAppId());
        assertNotEquals(first.getGeneratedCodeDir(), second.getGeneratedCodeDir());
    }

    @Test
    void invalidInputIsRejectedBeforeAnyServiceCall() {
        assertThrows(BusinessException.class, () -> workflow.executeWorkflow(" ", appId));
        assertThrows(BusinessException.class, () -> workflow.executeWorkflow("site", null));
        assertThrows(BusinessException.class, () -> workflow.executeWorkflow("site", 0L));
        assertThrows(BusinessException.class, () -> workflow.executeWorkflow("site", -1L));
        verifyNoInteractions(images, router, builder);
    }
}
