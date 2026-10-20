package com.hui.huiaicodemother.ai.tools;

import com.hui.huiaicodemother.constant.AppConstant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;

/** All model file operations are restricted to the current application's source directory. */
final class ProjectPathResolver {
    private static final Set<String> EXCLUDED = Set.of("node_modules", ".git", "dist", "build", "target");

    private ProjectPathResolver() {
    }

    static Path resolve(String relativePath, Long appId, boolean allowRoot) throws IOException {
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("应用 ID 无效");
        }
        if (relativePath == null || relativePath.isBlank()) {
            if (!allowRoot) throw new IllegalArgumentException("文件路径不能为空");
            relativePath = "";
        }
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute() || relativePath.contains(":") || relativePath.startsWith("\\")) {
            throw new IllegalArgumentException("只能使用项目内的相对路径");
        }
        Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId).toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || (!allowRoot && target.equals(root))) {
            throw new IllegalArgumentException("路径不能超出当前应用目录");
        }
        for (Path part : root.relativize(target)) {
            String name = part.toString().toLowerCase(java.util.Locale.ROOT);
            if (EXCLUDED.contains(name) || name.startsWith(".env")) {
                throw new IllegalArgumentException("不能操作依赖、构建产物或环境配置");
            }
        }
        // Reject links and Windows junctions in every existing ancestor, including the project root.
        for (Path current = target; current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS)))) {
                throw new IllegalArgumentException("不能通过链接访问文件");
            }
        }
        return target;
    }
}
