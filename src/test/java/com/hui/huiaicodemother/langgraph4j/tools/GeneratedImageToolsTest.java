package com.hui.huiaicodemother.langgraph4j.tools;

import com.alibaba.dashscope.aigc.imagesynthesis.*;
import com.hui.huiaicodemother.langgraph4j.model.enums.ImageCategoryEnum;
import com.hui.huiaicodemother.manager.CosManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeneratedImageToolsTest {
    @TempDir Path tempDir;

    @Test
    void logoUsesConfiguredModelAndMapsOnlyReturnedImageUrls() throws Exception {
        LogoGeneratorTool tool = new LogoGeneratorTool();
        ReflectionTestUtils.setField(tool, "dashScopeApiKey", "test-key");
        ReflectionTestUtils.setField(tool, "imageModel", "wan2.2-t2i-flash");
        ImageSynthesisResult result = mock(ImageSynthesisResult.class);
        ImageSynthesisOutput output = new ImageSynthesisOutput();
        output.setResults(List.of(Map.of("url", "https://example.test/logo.png"), Map.of("url", " ")));
        when(result.getOutput()).thenReturn(output);
        try (var construction = mockConstruction(ImageSynthesis.class,
                (sdk, context) -> when(sdk.call(any(ImageSynthesisParam.class))).thenReturn(result))) {
            var images = tool.generateLogos("A blue bird");
            assertEquals(1, images.size());
            assertEquals(ImageCategoryEnum.LOGO, images.getFirst().getCategory());
            assertEquals("https://example.test/logo.png", images.getFirst().getUrl());
            ArgumentCaptor<ImageSynthesisParam> parameter = ArgumentCaptor.forClass(ImageSynthesisParam.class);
            verify(construction.constructed().getFirst()).call(parameter.capture());
            assertEquals("wan2.2-t2i-flash", parameter.getValue().getModel());
            assertEquals(1, parameter.getValue().getN());
            assertTrue(parameter.getValue().getPrompt().contains("A blue bird"));
        }
    }

    @Test
    void logoProviderErrorsAreOptional() throws Exception {
        LogoGeneratorTool tool = new LogoGeneratorTool();
        ReflectionTestUtils.setField(tool, "dashScopeApiKey", "test-key");
        ReflectionTestUtils.setField(tool, "imageModel", "wan2.2-t2i-flash");
        try (var ignored = mockConstruction(ImageSynthesis.class,
                (sdk, context) -> when(sdk.call(any(ImageSynthesisParam.class)))
                        .thenThrow(new IllegalStateException("provider unavailable")))) {
            assertTrue(tool.generateLogos("a bird").isEmpty());
        }
    }

    @Test
    void diagramUploadReturnsResourceAndRemovesTemporarySvg() throws Exception {
        Path svg = Files.writeString(tempDir.resolve("diagram.svg"), "<svg/>");
        MermaidDiagramTool tool = spy(new MermaidDiagramTool());
        CosManager cos = mock(CosManager.class);
        ReflectionTestUtils.setField(tool, "cosManager", cos);
        doReturn(svg.toFile()).when(tool).convertMermaidToSvg(anyString());
        when(cos.uploadFile(anyString(), eq(svg.toFile()))).thenReturn("https://example.test/diagram.svg");
        var images = tool.generateMermaidDiagram("graph TD; A-->B", "Architecture");
        assertEquals(1, images.size());
        assertEquals(ImageCategoryEnum.ARCHITECTURE, images.getFirst().getCategory());
        assertEquals("https://example.test/diagram.svg", images.getFirst().getUrl());
        assertFalse(Files.exists(svg));
    }

    @Test
    void failedDiagramUploadStillRemovesTemporarySvg() throws Exception {
        Path svg = Files.writeString(tempDir.resolve("diagram.svg"), "<svg/>");
        MermaidDiagramTool tool = spy(new MermaidDiagramTool());
        CosManager cos = mock(CosManager.class);
        ReflectionTestUtils.setField(tool, "cosManager", cos);
        doReturn(svg.toFile()).when(tool).convertMermaidToSvg(anyString());
        when(cos.uploadFile(anyString(), any())).thenThrow(new IllegalStateException("upload failed"));
        assertTrue(tool.generateMermaidDiagram("graph TD; A-->B", "Architecture").isEmpty());
        assertFalse(Files.exists(svg));
    }
}
