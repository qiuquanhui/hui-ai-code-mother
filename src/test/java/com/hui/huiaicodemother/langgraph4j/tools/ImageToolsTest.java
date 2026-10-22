package com.hui.huiaicodemother.langgraph4j.tools;

import com.hui.huiaicodemother.langgraph4j.model.enums.ImageCategoryEnum;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Uses a local HTTP server to verify request encoding and provider response parsing. */
class ImageToolsTest {
    private HttpServer server;
    private int status = 200;
    private String response = "{}";
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    private String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private ImageSearchTool pexels() {
        ImageSearchTool tool = new ImageSearchTool();
        ReflectionTestUtils.setField(tool, "searchUrl", endpoint() + "/search");
        ReflectionTestUtils.setField(tool, "pexelsApiKey", "test-key");
        return tool;
    }

    private UndrawIllustrationTool undraw() {
        UndrawIllustrationTool tool = new UndrawIllustrationTool();
        ReflectionTestUtils.setField(tool, "searchUrl", endpoint() + "/search/%s.json?term=%s");
        return tool;
    }

    @Test
    void contentImagesIncludeCategoryUrlDescriptionAndCorrectQuery() {
        response = """
                {"photos":[{"alt":"A forest","src":{"medium":"https://example.test/forest.jpg"}}]}
                """;
        var images = pexels().searchContentImages("forest & river");
        assertEquals(1, images.size());
        assertEquals(ImageCategoryEnum.CONTENT, images.getFirst().getCategory());
        assertEquals("A forest", images.getFirst().getDescription());
        assertEquals("https://example.test/forest.jpg", images.getFirst().getUrl());
        assertEquals("test-key", authorization.get());
        assertTrue(query.get().contains("per_page=12"));
        assertTrue(query.get().contains("%26"));
    }

    @Test
    void contentProviderErrorsOrMissingPhotosProduceAnEmptyList() {
        ImageSearchTool tool = pexels();
        status = 429;
        assertTrue(tool.searchContentImages("forest").isEmpty());
        status = 200;
        assertTrue(tool.searchContentImages("forest").isEmpty());
        response = "invalid json";
        assertTrue(tool.searchContentImages("forest").isEmpty());
    }

    @Test
    void missingKeysOrBlankQueryDoNotCallContentProvider() {
        ImageSearchTool tool = pexels();
        assertTrue(tool.searchContentImages(" ").isEmpty());
        ReflectionTestUtils.setField(tool, "pexelsApiKey", "");
        assertTrue(tool.searchContentImages("forest").isEmpty());
        assertNull(query.get());
    }

    @Test
    void illustrationQueryIsEncodedAndBlankImagesAreIgnored() {
        response = """
                {"pageProps":{"initialResults":[{"title":"Team","media":"https://example.test/team.svg"},
                {"title":"Empty","media":""}]}}
                """;
        var images = undraw().searchIllustrations("team & work");
        assertEquals(1, images.size());
        assertEquals(ImageCategoryEnum.ILLUSTRATION, images.getFirst().getCategory());
        assertEquals("Team", images.getFirst().getDescription());
        assertEquals("https://example.test/team.svg", images.getFirst().getUrl());
        assertTrue(query.get().contains("%26"));
        assertFalse(query.get().contains(" "));
    }

    @Test
    void illustrationProviderErrorsAndEmptyResultsAreOptional() {
        UndrawIllustrationTool tool = undraw();
        assertTrue(tool.searchIllustrations(" ").isEmpty());
        assertNull(query.get());
        status = 404;
        assertTrue(tool.searchIllustrations("team").isEmpty());
        status = 200;
        assertTrue(tool.searchIllustrations("team").isEmpty());
        response = "{\"pageProps\":{\"initialResults\":[]}}";
        assertTrue(tool.searchIllustrations("team").isEmpty());
    }

    @Test
    void logoGenerationWithoutAKeyAndEmptyMermaidDoNotCallProviders() {
        assertTrue(new LogoGeneratorTool().generateLogos("a blue logo").isEmpty());
        assertTrue(new MermaidDiagramTool().generateMermaidDiagram(" ", "diagram").isEmpty());
    }
}
