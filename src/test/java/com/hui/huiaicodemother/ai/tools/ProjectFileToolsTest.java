package com.hui.huiaicodemother.ai.tools;

import com.hui.huiaicodemother.constant.AppConstant;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.Comparator;
import static org.junit.jupiter.api.Assertions.*;

class ProjectFileToolsTest {
    private final long appId = System.nanoTime();
    private final Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId).toAbsolutePath().normalize();
    private final FileWriteTool write = new FileWriteTool();
    private final FileReadTool read = new FileReadTool();
    private final FileModifyTool modify = new FileModifyTool();
    private final FileDeleteTool delete = new FileDeleteTool();

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(root)) {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    @Test
    void createReadModifyAndDeletePreserveUnicodeAndOtherContent() throws Exception {
        assertTrue(write.writeFile("src/标题.vue", "<h1>旧标题</h1><p>保持原样</p>", appId).contains("成功"));
        assertEquals("<h1>旧标题</h1><p>保持原样</p>", read.readFile("src/标题.vue", appId));
        assertTrue(modify.modifyFile("src/标题.vue", "旧标题", "新标题", appId).contains("成功"));
        assertEquals("<h1>新标题</h1><p>保持原样</p>", Files.readString(root.resolve("src/标题.vue")));
        assertTrue(delete.deleteFile("src/标题.vue", appId).contains("成功"));
        assertFalse(Files.exists(root.resolve("src/标题.vue")));
    }

    @Test
    void allOperationsRejectEscapesAbsolutePathsAndOtherApplications() throws Exception {
        for (String path : new String[]{"../outside.txt", "../vue_project_1/file.txt", root.resolve("absolute.txt").toString(), "C:outside.txt"}) {
            assertThrows(IllegalArgumentException.class, () -> ProjectPathResolver.resolve(path, appId, false));
            assertFalse(write.writeFile(path, "bad", appId).contains("成功"));
            assertFalse(read.readFile(path, appId).contains("成功"));
            assertFalse(modify.modifyFile(path, "a", "b", appId).contains("成功"));
            assertFalse(delete.deleteFile(path, appId).contains("成功"));
        }
    }

    @Test
    void ambiguousEmptyAndMissingReplacementsDoNotChangeFile() throws Exception {
        write.writeFile("src/a.vue", "same same", appId);
        assertTrue(modify.modifyFile("src/a.vue", "same", "changed", appId).contains("匹配多处"));
        assertTrue(modify.modifyFile("src/a.vue", "", "changed", appId).contains("不能为空"));
        assertTrue(modify.modifyFile("src/a.vue", "absent", "changed", appId).contains("未找到"));
        assertEquals("same same", Files.readString(root.resolve("src/a.vue")));
    }

    @Test
    void directoryListingShowsPathsAndPrunesDependenciesAndSecrets() throws Exception {
        write.writeFile("src/pages/Home.vue", "<template/>", appId);
        Files.createDirectories(root.resolve("node_modules/deep"));
        Files.writeString(root.resolve("node_modules/deep/hidden.js"), "hidden");
        Files.writeString(root.resolve(".env.local"), "secret");
        String listing = new FileDirReadTool().readDir("", appId);
        assertTrue(listing.contains("src/pages/Home.vue\n"));
        assertFalse(listing.contains("hidden"));
        assertFalse(listing.contains(".env"));
        assertFalse(new FileDirReadTool().readDir("node_modules", appId).contains("hidden"));
        assertThrows(IllegalArgumentException.class, () -> ProjectPathResolver.resolve(".env.local", appId, false));
        assertThrows(IllegalArgumentException.class, () -> ProjectPathResolver.resolve("NODE_MODULES/a.js", appId, false));
        assertThrows(IllegalArgumentException.class, () -> ProjectPathResolver.resolve(".ENV.LOCAL", appId, false));
    }

    @Test
    void importantFilesCannotBeDeletedAndInvalidArgumentsReturnErrors() throws Exception {
        write.writeFile("package.json", "{}", appId);
        assertTrue(delete.deleteFile("package.json", appId).contains("不允许"));
        assertTrue(Files.exists(root.resolve("package.json")));
        assertFalse(write.writeFile(null, "content", appId).contains("成功"));
        assertFalse(write.writeFile("a.txt", null, appId).contains("成功"));
        assertThrows(IllegalArgumentException.class, () -> ProjectPathResolver.resolve("a", 0L, false));
    }

    @Test
    void managerRegistersAllFiveModelTools() {
        ToolManager manager = new ToolManager();
        ReflectionTestUtils.setField(manager, "tools", new BaseTool[]{write, read, modify, delete, new FileDirReadTool()});
        manager.initTools();
        assertEquals(5, manager.getAllTools().length);
        assertSame(modify, manager.getTool("modifyFile"));
        assertNull(manager.getTool("unknown"));
    }
}
