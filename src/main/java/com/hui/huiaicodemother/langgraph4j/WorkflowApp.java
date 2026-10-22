package com.hui.huiaicodemother.langgraph4j;

import com.hui.huiaicodemother.HuiAiCodeMotherApplication;
import org.springframework.boot.SpringApplication;

/** Run the complete workflow with its Spring services initialized. */
public class WorkflowApp {
    public static void main(String[] args) {
        try (var application = SpringApplication.run(HuiAiCodeMotherApplication.class, args)) {
            application.getBean(CodeGenWorkflow.class).executeWorkflow("Create a personal blog website");
        }
    }
}
