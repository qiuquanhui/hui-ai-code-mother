package com.hui.huiaicodemother.langgraph4j;

import com.hui.huiaicodemother.langgraph4j.model.*;
import com.hui.huiaicodemother.langgraph4j.model.enums.ImageCategoryEnum;
import com.hui.huiaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

abstract class ConcurrentWorkflowContract extends WorkflowTestSupport {
    abstract CodeGenWorkflow variant();

    ImageCollectionPlan plan() {
        ImageCollectionPlan plan = new ImageCollectionPlan();
        plan.setContentImageTasks(List.of(new ImageCollectionPlan.ImageSearchTask("nature")));
        plan.setIllustrationTasks(List.of(new ImageCollectionPlan.IllustrationTask("team")));
        plan.setDiagramTasks(List.of(new ImageCollectionPlan.DiagramTask("graph TD; A-->B", "system")));
        plan.setLogoTasks(List.of(new ImageCollectionPlan.LogoTask("brand")));
        return plan;
    }

    List<ImageResource> resource(ImageCategoryEnum category) {
        return List.of(ImageResource.builder().category(category).description(category.getText())
                .url("https://images.example.test/" + category.getValue() + ".png").build());
    }

    void configureTools() {
        when(planner.planImageCollection(anyString())).thenReturn(plan());
        when(contentTool.searchContentImages(anyString())).thenReturn(resource(ImageCategoryEnum.CONTENT));
        when(illustrationTool.searchIllustrations(anyString())).thenReturn(resource(ImageCategoryEnum.ILLUSTRATION));
        when(diagramTool.generateMermaidDiagram(anyString(), anyString())).thenReturn(resource(ImageCategoryEnum.ARCHITECTURE));
        when(logoTool.generateLogos(anyString())).thenReturn(resource(ImageCategoryEnum.LOGO));
    }

    @ParameterizedTest
    @EnumSource(CodeGenTypeEnum.class)
    void allImageBranchesRunConcurrentlyAndReachQualityAndCorrectOutput(CodeGenTypeEnum type) {
        when(router.routeCodeGenType(anyString())).thenReturn(type);
        configureTools();
        CountDownLatch ready = new CountDownLatch(4);
        when(contentTool.searchContentImages(anyString())).thenAnswer(inv -> parallelResource(ready, ImageCategoryEnum.CONTENT));
        when(illustrationTool.searchIllustrations(anyString())).thenAnswer(inv -> parallelResource(ready, ImageCategoryEnum.ILLUSTRATION));
        when(diagramTool.generateMermaidDiagram(anyString(), anyString())).thenAnswer(inv -> parallelResource(ready, ImageCategoryEnum.ARCHITECTURE));
        when(logoTool.generateLogos(anyString())).thenAnswer(inv -> parallelResource(ready, ImageCategoryEnum.LOGO));
        Path root = output(type, appId);
        if (type == CodeGenTypeEnum.VUE_PROJECT) mockVueGeneration(true, true);
        var result = variant().executeWorkflow("Build a website", appId);
        assertEquals(4, result.getImageList().size());
        assertEquals(1, result.getContentImages().size());
        assertEquals(1, result.getIllustrations().size());
        assertEquals(1, result.getDiagrams().size());
        assertEquals(1, result.getLogos().size());
        assertEquals(1, result.getGenerationAttempts());
        for (ImageCategoryEnum category : ImageCategoryEnum.values()) {
            assertTrue(result.getEnhancedPrompt().contains("/" + category.getValue() + ".png"));
        }
        Path built = type == CodeGenTypeEnum.VUE_PROJECT ? root.resolve("dist") : root;
        assertEquals(built, Path.of(result.getBuildResultDir()));
        assertTrue(Files.isRegularFile(built.resolve("index.html")));
        verify(quality, times(1)).checkCodeQuality(anyString());
        verify(planner, times(1)).planImageCollection("Build a website");
        verifyNoInteractions(images);
    }

    private List<ImageResource> parallelResource(CountDownLatch ready, ImageCategoryEnum category) throws Exception {
        ready.countDown();
        assertTrue(ready.await(5, TimeUnit.SECONDS), "The four image branches must overlap");
        return resource(category);
    }

    @Test
    void oneProviderFailureDoesNotLoseOtherBranchResults() {
        configureTools();
        when(contentTool.searchContentImages(anyString())).thenThrow(new IllegalStateException("unavailable"));
        output(CodeGenTypeEnum.HTML, appId);
        var result = variant().executeWorkflow("site", appId);
        assertEquals(3, result.getImageList().size());
        assertTrue(result.getContentImages().isEmpty());
        assertNotNull(result.getBuildResultDir());
    }

    @Test
    void unavailablePlannerFallsBackToNoImageTasks() {
        when(planner.planImageCollection(anyString())).thenThrow(new IllegalStateException("planner unavailable"));
        output(CodeGenTypeEnum.HTML, appId);
        var result = variant().executeWorkflow("site", appId);
        assertTrue(result.getImageList().isEmpty());
        assertEquals("site", result.getEnhancedPrompt());
        verifyNoInteractions(contentTool, illustrationTool, diagramTool, logoTool);
    }

    @Test
    void followingRunCannotReuseThePreviousRunsImageResults() {
        configureTools();
        output(CodeGenTypeEnum.HTML, appId);
        output(CodeGenTypeEnum.HTML, appId + 1);
        CodeGenWorkflow graph = variant();
        assertEquals(4, graph.executeWorkflow("first", appId).getImageList().size());
        when(planner.planImageCollection(anyString())).thenReturn(new ImageCollectionPlan());
        assertTrue(graph.executeWorkflow("second", appId + 1).getImageList().isEmpty());
    }

    @Test
    void qualityRepairAndRetryLimitAlsoApplyToParallelGraphs() {
        output(CodeGenTypeEnum.HTML, appId);
        when(quality.checkCodeQuality(anyString())).thenReturn(QualityResult.builder().isValid(false).build());
        assertThrows(RuntimeException.class, () -> variant().executeWorkflow("site", appId));
        verify(ai, times(3)).generateHtmlCodeStream(anyString());
    }
}
