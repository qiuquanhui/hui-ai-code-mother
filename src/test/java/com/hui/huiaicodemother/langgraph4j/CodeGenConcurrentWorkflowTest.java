package com.hui.huiaicodemother.langgraph4j;

class CodeGenConcurrentWorkflowTest extends ConcurrentWorkflowContract {
    @Override
    CodeGenWorkflow variant() { return new CodeGenConcurrentWorkflow(); }
}
