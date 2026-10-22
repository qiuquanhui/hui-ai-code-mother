package com.hui.huiaicodemother.langgraph4j;

import com.hui.huiaicodemother.ai.*;
import com.hui.huiaicodemother.constant.AppConstant;
import com.hui.huiaicodemother.core.AiCodeGeneratorFacade;
import com.hui.huiaicodemother.core.builder.VueProjectBuilder;
import com.hui.huiaicodemother.exception.BusinessException;
import com.hui.huiaicodemother.langgraph4j.ai.ImageCollectionService;
import com.hui.huiaicodemother.langgraph4j.state.WorkflowContext;
import com.hui.huiaicodemother.langgraph4j.model.ImageCollectionPlan;
import com.hui.huiaicodemother.langgraph4j.model.ImageResource;
import com.hui.huiaicodemother.langgraph4j.model.enums.ImageCategoryEnum;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.mockito.ArgumentCaptor;
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
class CodeGenWorkflowTest extends WorkflowTestSupport {
    @ParameterizedTest
    @EnumSource(CodeGenTypeEnum.class)
    void executesImagePlanningAndPersistsEachGenerationType(CodeGenTypeEnum type) throws Exception {
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
        verify(planner, times(1)).planImageCollection("Build a website");
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
        when(planner.planImageCollection(anyString())).thenThrow(new IllegalStateException("provider unavailable"));
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
        verifyNoInteractions(images, planner, router, builder);
    }

    @Test
    void serialGraphsImageNodeExecutesPlannedProviderTasksInParallel() throws Exception {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("nature")));
        plan.setIllustrationTasks(List.of(new ImageCollectionPlan.IllustrationTask("team")));
        plan.setDiagramTasks(List.of(new ImageCollectionPlan.DiagramTask("graph TD; A-->B", "system")));
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("brand")));
        when(planner.planImageCollection(anyString())).thenReturn(plan);
        CountDownLatch ready = new CountDownLatch(4);
        var resource = List.of(ImageResource.builder().category(ImageCategoryEnum.CONTENT)
                .description("Image").url("https://images.example.test/asset.png").build());
        org.mockito.stubbing.Answer<List<ImageResource>> collect = invocation -> {
            ready.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            return resource;
        };
        when(contentTool.searchContentImages(anyString())).thenAnswer(collect);
        when(illustrationTool.searchIllustrations(anyString())).thenAnswer(collect);
        when(diagramTool.generateMermaidDiagram(anyString(), anyString())).thenAnswer(collect);
        when(logoTool.generateLogos(anyString())).thenAnswer(collect);
        assertEquals(4, workflow.executeWorkflow("site", appId).getImageList().size());
        verifyNoInteractions(images);
    }

    @Test
    void serialImageCollectionRetainsSuccessfulTasksWhenAnotherTaskFails() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("fail")));
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("brand")));
        when(planner.planImageCollection(anyString())).thenReturn(plan);
        when(contentTool.searchContentImages(anyString())).thenThrow(new IllegalStateException("provider unavailable"));
        when(logoTool.generateLogos(anyString())).thenReturn(List.of(ImageResource.builder()
                .category(ImageCategoryEnum.LOGO).description("Logo").url("https://images.example.test/logo.png").build()));
        var result = workflow.executeWorkflow("site", appId);
        assertEquals(1, result.getImageList().size());
        assertTrue(result.getEnhancedPrompt().contains("logo.png"));
    }
}
