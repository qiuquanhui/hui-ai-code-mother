package com.hui.huiaicodemother.langgraph4j.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CodeQualityCheckNodeTest {
    @TempDir Path root;

    @Test
    void readsSourceFilesButPrunesDependenciesBuildOutputsAndHiddenDirectories() throws Exception {
        Files.writeString(root.resolve("index.html"), "<h1>source</h1>");
        Path src = Files.createDirectories(root.resolve("src"));
        Files.writeString(src.resolve("App.vue"), "<template>app</template>");
        for (String directory : new String[]{"node_modules", "dist", "target", "build", ".git", ".private"}) {
            Path excluded = Files.createDirectories(root.resolve(directory));
            Files.writeString(excluded.resolve("large.js"), "excluded".repeat(50000));
        }
        Files.writeString(root.resolve(".secrets.json"), "private-value");
        Files.writeString(root.resolve("readme.md"), "not code");
        String content = CodeQualityCheckNode.readAndConcatenateCodeFiles(root.toString());
        assertTrue(content.contains("<h1>source</h1>"));
        assertTrue(content.contains("<template>app</template>"));
        assertFalse(content.contains("excluded"));
        assertFalse(content.contains("private-value"));
        assertFalse(content.contains("not code"));
    }

    @Test
    void emptyAndMissingProjectsAreNotMadeNonemptyByAHeader() throws Exception {
        assertEquals("", CodeQualityCheckNode.readAndConcatenateCodeFiles(root.toString()));
        Files.writeString(root.resolve("index.html"), "  ");
        assertEquals("", CodeQualityCheckNode.readAndConcatenateCodeFiles(root.toString()));
        assertEquals("", CodeQualityCheckNode.readAndConcatenateCodeFiles(root.resolve("missing").toString()));
    }

    @Test
    void oversizedSourceIsRejectedInsteadOfSendingAnUnboundedModelRequest() throws Exception {
        Files.writeString(root.resolve("large.js"), "x".repeat(256 * 1024 + 1));
        assertThrows(IOException.class, () -> CodeQualityCheckNode.readAndConcatenateCodeFiles(root.toString()));
    }

    @Test
    void tooManyCodeFilesAreRejected() throws Exception {
        for (int i = 0; i < 201; i++) Files.writeString(root.resolve(i + ".js"), "x");
        assertThrows(IOException.class, () -> CodeQualityCheckNode.readAndConcatenateCodeFiles(root.toString()));
    }
}
