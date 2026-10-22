package com.hui.huiaicodemother.langgraph4j.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.system.SystemUtil;
import com.hui.huiaicodemother.exception.BusinessException;
import com.hui.huiaicodemother.exception.ErrorCode;
import com.hui.huiaicodemother.langgraph4j.model.ImageResource;
import com.hui.huiaicodemother.langgraph4j.model.enums.ImageCategoryEnum;
import com.hui.huiaicodemother.manager.CosManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Mermaid 架构图生成工具
 */
@Slf4j
@Component
public class MermaidDiagramTool {

    @Resource
    private CosManager cosManager;

    @Tool("将 Mermaid 代码转换为架构图图片，用于展示系统结构和技术关系")
    public List<ImageResource> generateMermaidDiagram(@P("Mermaid 图表代码") String mermaidCode,
                                                      @P("架构图描述") String description) {
        if (StrUtil.isBlank(mermaidCode)) {
            return new ArrayList<>();
        }
        File diagramFile = null;
        try {
            // 转换为SVG图片
            diagramFile = convertMermaidToSvg(mermaidCode);
            // 上传到COS
            String keyName = String.format("/mermaid/%s/%s",
                    RandomUtil.randomString(5), diagramFile.getName());
            String cosUrl = cosManager.uploadFile(keyName, diagramFile);
            if (StrUtil.isNotBlank(cosUrl)) {
                return Collections.singletonList(ImageResource.builder()
                        .category(ImageCategoryEnum.ARCHITECTURE)
                        .description(description)
                        .url(cosUrl)
                        .build());
            }
        } catch (Exception e) {
            log.error("生成架构图失败: {}", e.getMessage(), e);
        } finally {
            if (diagramFile != null) FileUtil.del(diagramFile);
        }
        return new ArrayList<>();
    }

    /**
     * 将Mermaid代码转换为SVG图片
     */
    File convertMermaidToSvg(String mermaidCode) throws Exception {
        File tempInputFile = FileUtil.createTempFile("mermaid_input_", ".mmd", true);
        FileUtil.writeUtf8String(mermaidCode, tempInputFile);
        File tempOutputFile = FileUtil.createTempFile("mermaid_output_", ".svg", true);
        Process process = null;
        boolean success = false;
        try {
            List<String> command = new ArrayList<>();
            if (SystemUtil.getOsInfo().isWindows()) command.addAll(List.of("cmd.exe", "/d", "/c", "mmdc.cmd"));
            else command.add("mmdc");
            command.addAll(List.of("-i", tempInputFile.getAbsolutePath(), "-o",
                    tempOutputFile.getAbsolutePath(), "-b", "transparent"));
            process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0
                    || Files.size(tempOutputFile.toPath()) == 0) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Mermaid CLI 执行失败");
            }
            success = true;
            return tempOutputFile;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            FileUtil.del(tempInputFile);
            if (!success) FileUtil.del(tempOutputFile);
        }
    }
}
