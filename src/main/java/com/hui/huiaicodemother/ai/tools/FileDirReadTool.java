package com.hui.huiaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Set;

/**
 * 文件目录读取工具
 * 过滤依赖目录并限制返回规模
 */
@Slf4j
@Component
public class FileDirReadTool extends BaseTool {

    /**
     * 需要忽略的文件和目录
     */
    private static final Set<String> IGNORED_NAMES = Set.of(
            "node_modules", ".git", "dist", "build", ".ds_store",
            ".env", "target", ".mvn", ".idea", ".vscode", "coverage"
    );

    /**
     * 需要忽略的文件扩展名
     */
    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log", ".tmp", ".cache", ".lock"
    );

    @Tool("读取目录结构，获取指定目录下的所有文件和子目录信息")
    public String readDir(
            @P("目录的相对路径，为空则读取整个项目结构")
            String relativeDirPath,
            @ToolMemoryId Long appId
    ) {
        try {
            Path path = ProjectPathResolver.resolve(relativeDirPath, appId, true);
            File targetDir = path.toFile();
            if (!targetDir.exists() || !targetDir.isDirectory()) {
                return "错误：目录不存在或不是目录 - " + relativeDirPath;
            }
            final Path pathRoot = path;
            StringBuilder structure = new StringBuilder();
            structure.append("项目目录结构:\n");
            // Prune dependency directories before visiting their children; never follow links.
            java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
            Files.walkFileTree(path, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 12,
                    new java.nio.file.SimpleFileVisitor<>() {
                        @Override
                        public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (!dir.equals(pathRoot) && (shouldIgnore(dir.getFileName().toString()) || Files.isSymbolicLink(dir))) {
                                return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (!attrs.isRegularFile() || shouldIgnore(file.getFileName().toString())) {
                                return java.nio.file.FileVisitResult.CONTINUE;
                            }
                            structure.append(pathRoot.relativize(file).toString().replace('\\', '/')).append('\n');
                            if (count.incrementAndGet() >= 500) {
                                structure.append("... 目录过大，已截断，请读取具体子目录\n");
                                return java.nio.file.FileVisitResult.TERMINATE;
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
            return structure.toString();
        } catch (Exception e) {
            String errorMessage = "读取目录结构失败: " + relativeDirPath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    /**
     * 判断是否应该忽略该文件或目录
     */
    private boolean shouldIgnore(String fileName) {
        fileName = fileName.toLowerCase(java.util.Locale.ROOT);
        // 检查是否在忽略名称列表中
        if (IGNORED_NAMES.contains(fileName) || fileName.startsWith(".env")) {
            return true;
        }

        // 检查文件扩展名
        return IGNORED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    @Override
    public String getToolName() {
        return "readDir";
    }

    @Override
    public String getDisplayName() {
        return "读取目录";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeDirPath = arguments.getStr("relativeDirPath");
        if (StrUtil.isEmpty(relativeDirPath)) {
            relativeDirPath = "根目录";
        }
        return String.format("[工具调用] %s %s", getDisplayName(), relativeDirPath);
    }
}
