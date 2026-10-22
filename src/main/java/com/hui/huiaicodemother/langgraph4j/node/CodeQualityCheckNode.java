package com.hui.huiaicodemother.langgraph4j.node;

import com.hui.huiaicodemother.exception.BusinessException;
import com.hui.huiaicodemother.exception.ErrorCode;
import com.hui.huiaicodemother.langgraph4j.ai.CodeQualityCheckService;
import com.hui.huiaicodemother.langgraph4j.model.QualityResult;
import com.hui.huiaicodemother.langgraph4j.state.WorkflowContext;
import com.hui.huiaicodemother.model.enums.CodeGenTypeEnum;
import com.hui.huiaicodemother.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Slf4j
public class CodeQualityCheckNode {
    private static final Set<String> EXTENSIONS = Set.of("html", "htm", "css", "js", "json", "vue", "ts", "jsx", "tsx");
    private static final Set<String> EXCLUDED = Set.of("node_modules", "dist", "target", "build");
    private static final int MAX_FILES = 200;
    private static final int MAX_BYTES = 256 * 1024;

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            QualityResult result;
            try {
                String directory = context.getGeneratedCodeDir();
                String code = readAndConcatenateCodeFiles(directory);
                if (code.isBlank() || !Files.isRegularFile(Path.of(directory, "index.html"), LinkOption.NOFOLLOW_LINKS)) {
                    result = invalid("未找到完整的项目源码或 index.html");
                } else {
                    result = SpringContextUtil.getBean(CodeQualityCheckService.class).checkCodeQuality(code);
                    if (result == null || result.getIsValid() == null) result = invalid("质检未返回有效的判断结果");
                }
            } catch (Exception e) {
                log.error("代码质检执行失败", e);
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "代码质检执行失败");
            }
            context.setQualityResult(result);
            context.setCurrentStep("代码质量检查");
            if (Boolean.TRUE.equals(result.getIsValid()) && context.getGenerationType() != CodeGenTypeEnum.VUE_PROJECT) {
                context.setBuildResultDir(context.getGeneratedCodeDir());
            }
            return WorkflowContext.saveContext(context);
        });
    }

    private static QualityResult invalid(String error) {
        return QualityResult.builder().isValid(false).errors(List.of(error))
                .suggestions(List.of("请按原始需求重新生成完整代码")).build();
    }

    /** Do not traverse dependencies, build outputs, hidden paths or symbolic links. */
    static String readAndConcatenateCodeFiles(String codeDir) throws IOException {
        if (codeDir == null || codeDir.isBlank()) return "";
        Path root = Path.of(codeDir);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return "";
        StringBuilder content = new StringBuilder();
        int[] count = {0};
        long[] size = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                String name = directory.getFileName().toString();
                if (!directory.equals(root) && (name.startsWith(".") || EXCLUDED.contains(name))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (!attributes.isRegularFile() || name.startsWith(".") || dot < 0
                        || !EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                    return FileVisitResult.CONTINUE;
                }
                if (++count[0] > MAX_FILES || (size[0] += attributes.size()) > MAX_BYTES) {
                    throw new IOException("项目超过单次质检的文件数或大小上限");
                }
                String code = Files.readString(file);
                if (!code.isBlank()) content.append("## 文件: ").append(root.relativize(file)).append("\n")
                        .append(code).append("\n\n");
                return FileVisitResult.CONTINUE;
            }
        });
        return content.toString();
    }
}
