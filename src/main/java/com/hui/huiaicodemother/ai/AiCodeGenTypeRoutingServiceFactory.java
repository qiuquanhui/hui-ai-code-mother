package com.hui.huiaicodemother.ai;

import com.hui.huiaicodemother.config.RoutingAiModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI代码生成类型路由服务工厂
 *
 * @author yupi
 */
@Slf4j
@Configuration
public class AiCodeGenTypeRoutingServiceFactory {

    @Resource
    private RoutingAiModelConfig routingAiModelConfig;

    /**
     * 创建AI代码生成类型路由服务实例
     */
    public AiCodeGenTypeRoutingService createAiCodeGenTypeRoutingService() {
        // 通过配置类代理调用 prototype Bean 方法，每次获取新的路由 ChatModel
        ChatModel chatModel = routingAiModelConfig.routingChatModelPrototype();
        return AiServices.builder(AiCodeGenTypeRoutingService.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * 默认提供一个 Bean
     */
    @Bean
    public AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService() {
        return createAiCodeGenTypeRoutingService();
    }
}
