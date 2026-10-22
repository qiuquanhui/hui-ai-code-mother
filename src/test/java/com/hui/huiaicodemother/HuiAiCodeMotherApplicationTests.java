package com.hui.huiaicodemother;

import com.hui.huiaicodemother.langgraph4j.CodeGenWorkflow;
import com.hui.huiaicodemother.langgraph4j.ai.ImageCollectionService;
import com.hui.huiaicodemother.utils.SpringContextUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HuiAiCodeMotherApplicationTests {
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertNotNull(applicationContext.getBean(ImageCollectionService.class));
        assertSame(applicationContext.getBean(CodeGenWorkflow.class), SpringContextUtil.getBean(CodeGenWorkflow.class));
    }

}
