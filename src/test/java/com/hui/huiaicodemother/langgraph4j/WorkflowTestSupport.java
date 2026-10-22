package com.hui.huiaicodemother.langgraph4j;

import com.hui.huiaicodemother.ai.*;
import com.hui.huiaicodemother.constant.AppConstant;
import com.hui.huiaicodemother.core.AiCodeGeneratorFacade;
import com.hui.huiaicodemother.core.builder.VueProjectBuilder;
import com.hui.huiaicodemother.exception.BusinessException;
import com.hui.huiaicodemother.langgraph4j.ai.ImageCollectionService;
import com.hui.huiaicodemother.langgraph4j.state.WorkflowContext;
import com.hui.huiaicodemother.model.enums.CodeGenTypeEnum;
import com.hui.huiaicodemother.utils.SpringContextUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Test-only model and provider substitutions; files and compiled graphs remain real. */
abstract class WorkflowTestSupport {
    protected final com.hui.huiaicodemother.langgraph4j.ai.ImageCollectionPlanService planner = mock(com.hui.huiaicodemother.langgraph4j.ai.ImageCollectionPlanService.class);
    protected final com.hui.huiaicodemother.langgraph4j.tools.ImageSearchTool contentTool = mock(com.hui.huiaicodemother.langgraph4j.tools.ImageSearchTool.class);
    protected final com.hui.huiaicodemother.langgraph4j.tools.UndrawIllustrationTool illustrationTool = mock(com.hui.huiaicodemother.langgraph4j.tools.UndrawIllustrationTool.class);
    protected final com.hui.huiaicodemother.langgraph4j.tools.MermaidDiagramTool diagramTool = mock(com.hui.huiaicodemother.langgraph4j.tools.MermaidDiagramTool.class);
    protected final com.hui.huiaicodemother.langgraph4j.tools.LogoGeneratorTool logoTool = mock(com.hui.huiaicodemother.langgraph4j.tools.LogoGeneratorTool.class);
    protected final ImageCollectionService images = mock(ImageCollectionService.class);
    protected final AiCodeGenTypeRoutingService router = mock(AiCodeGenTypeRoutingService.class);
    protected final VueProjectBuilder builder = mock(VueProjectBuilder.class);
    protected final AiCodeGeneratorService ai = mock(AiCodeGeneratorService.class);
    protected final AiCodeGeneratorFacade facade = spy(new AiCodeGeneratorFacade());
    protected final CodeGenWorkflow workflow = new CodeGenWorkflow();
    protected final List<Path> outputs = Collections.synchronizedList(new ArrayList<>());
    protected GenericApplicationContext application;
    protected ApplicationContext previousContext;
    protected long appId;

    @BeforeEach
    void setup() {
        previousContext = (ApplicationContext) ReflectionTestUtils.getField(SpringContextUtil.class, "applicationContext");
        appId = Math.abs(System.nanoTime());
        AiCodeGeneratorServiceFactory factory = mock(AiCodeGeneratorServiceFactory.class);
        when(factory.getAiCodeGeneratorService(anyLong(), any())).thenAnswer(invocation -> {
            output(invocation.getArgument(1), invocation.getArgument(0));
            return ai;
        });
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);
        when(images.collectImages(anyString())).thenReturn("https://images.example.test/hero.png");
        when(router.routeCodeGenType(anyString())).thenReturn(CodeGenTypeEnum.HTML);
        when(ai.generateHtmlCodeStream(anyString())).thenReturn(Flux.just("```html\n<h1>generated</h1>\n```"));
        when(ai.generateMultiFileCodeStream(anyString())).thenReturn(Flux.just(
                "```html\n<h1>generated</h1>\n```\n```css\nh1{color:red}\n```\n```javascript\nconsole.log(1)\n```"));
        var defaultPlan = new com.hui.huiaicodemother.langgraph4j.model.ImageCollectionPlan();
        defaultPlan.setContentImageTasks(List.of(new com.hui.huiaicodemother.langgraph4j.model.ImageCollectionPlan.ImageSearchTask("hero")));
        when(planner.planImageCollection(anyString())).thenReturn(defaultPlan);
        when(contentTool.searchContentImages(anyString())).thenReturn(List.of(com.hui.huiaicodemother.langgraph4j.model.ImageResource.builder()
                .category(com.hui.huiaicodemother.langgraph4j.model.enums.ImageCategoryEnum.CONTENT)
                .description("Hero").url("https://images.example.test/hero.png").build()));
        application = new GenericApplicationContext();
        application.getBeanFactory().registerSingleton("planner", planner);
        application.getBeanFactory().registerSingleton("contentTool", contentTool);
        application.getBeanFactory().registerSingleton("illustrationTool", illustrationTool);
        application.getBeanFactory().registerSingleton("diagramTool", diagramTool);
        application.getBeanFactory().registerSingleton("logoTool", logoTool);
        application.getBeanFactory().registerSingleton("images", images);
        application.getBeanFactory().registerSingleton("router", router);
        application.getBeanFactory().registerSingleton("facade", facade);
        application.getBeanFactory().registerSingleton("builder", builder);
        application.refresh();
        new SpringContextUtil().setApplicationContext(application);
    }

    @AfterEach
    void cleanup() throws Exception {
        new SpringContextUtil().setApplicationContext(previousContext);
        application.close();
        for (Path output : outputs) if (Files.exists(output)) {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    protected Path output(CodeGenTypeEnum type, long id) {
        Path path = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, type.getValue() + "_" + id);
        outputs.add(path);
        return path;
    }

    protected void mockVueGeneration(boolean buildSuccess, boolean produceDist) {
        doAnswer(invocation -> Flux.defer(() -> {
            try {
                Path root = output(CodeGenTypeEnum.VUE_PROJECT, invocation.getArgument(2));
                Files.createDirectories(root);
                Files.writeString(root.resolve("index.html"), "<div id='app'></div>");
                return Flux.just("generated");
            } catch (Exception e) { return Flux.error(e); }
        })).when(facade).generateAndSaveCodeStream(anyString(), eq(CodeGenTypeEnum.VUE_PROJECT), anyLong());
        when(builder.buildProject(anyString())).thenAnswer(invocation -> {
            if (produceDist) {
                Path dist = Path.of(invocation.getArgument(0, String.class), "dist");
                Files.createDirectories(dist);
                Files.writeString(dist.resolve("index.html"), "built page");
            }
            return buildSuccess;
        });
    }

}
