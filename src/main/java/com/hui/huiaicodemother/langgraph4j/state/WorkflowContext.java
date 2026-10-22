package com.hui.huiaicodemother.langgraph4j.state;

import com.hui.huiaicodemother.langgraph4j.model.ImageResource;
import com.hui.huiaicodemother.langgraph4j.model.ImageCollectionPlan;
import com.hui.huiaicodemother.langgraph4j.model.QualityResult;
import com.hui.huiaicodemother.model.enums.CodeGenTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 工作流上下文 - 存储所有状态信息
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowContext implements Serializable {

    /**
     * WorkflowContext 在 MessagesState 中的存储key
     */
    public static final String WORKFLOW_CONTEXT_KEY = "workflowContext";
    public static final String CONTENT_IMAGES_KEY = "contentImages";
    public static final String ILLUSTRATIONS_KEY = "illustrations";
    public static final String DIAGRAMS_KEY = "diagrams";
    public static final String LOGOS_KEY = "logos";

    /** 应用 ID，用于隔离生成目录和对话记忆。 */
    private Long appId;

    /**
     * 当前执行步骤
     */
    private String currentStep;

    /**
     * 用户原始输入的提示词
     */
    private String originalPrompt;

    /**
     * 图片资源字符串
     */
    private String imageListStr;

    /**
     * 图片资源列表
     */
    private List<ImageResource> imageList;

    /**
     * 增强后的提示词
     */
    private String enhancedPrompt;

    /**
     * 代码生成类型
     */
    private CodeGenTypeEnum generationType;

    /**
     * 生成的代码目录
     */
    private String generatedCodeDir;

    /**
     * 构建成功的目录
     */
    private String buildResultDir;

    /**
     * 错误信息
     */
    private String errorMessage;

    private QualityResult qualityResult;
    private ImageCollectionPlan imageCollectionPlan;
    private List<ImageResource> contentImages;
    private List<ImageResource> illustrations;
    private List<ImageResource> diagrams;
    private List<ImageResource> logos;
    private int generationAttempts;
    @Builder.Default
    private int maxGenerationAttempts = 3;

    @Serial
    private static final long serialVersionUID = 1L;

    // ========== 上下文操作方法 ==========

    /**
     * 从 MessagesState 中获取 WorkflowContext
     */
    public static WorkflowContext getContext(MessagesState<String> state) {
        WorkflowContext saved = (WorkflowContext) state.data().get(WORKFLOW_CONTEXT_KEY);
        if (saved == null) return null;
        // Parallel branches write distinct state keys, never the shared context object.
        WorkflowContext context = saved.toBuilder().build();
        if (state.data().containsKey(CONTENT_IMAGES_KEY)) context.setContentImages(images(state, CONTENT_IMAGES_KEY));
        if (state.data().containsKey(ILLUSTRATIONS_KEY)) context.setIllustrations(images(state, ILLUSTRATIONS_KEY));
        if (state.data().containsKey(DIAGRAMS_KEY)) context.setDiagrams(images(state, DIAGRAMS_KEY));
        if (state.data().containsKey(LOGOS_KEY)) context.setLogos(images(state, LOGOS_KEY));
        return context;
    }

    @SuppressWarnings("unchecked")
    private static List<ImageResource> images(MessagesState<String> state, String key) {
        return List.copyOf((List<ImageResource>) state.data().get(key));
    }

    /**
     * 将 WorkflowContext 保存到 MessagesState 中
     */
    public static Map<String, Object> saveContext(WorkflowContext context) {
        return Map.of(WORKFLOW_CONTEXT_KEY, context);
    }
}
