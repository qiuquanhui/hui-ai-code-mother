package com.hui.huiaicodemother.langgraph4j;

class CodeGenSubgraphWorkflowTest extends ConcurrentWorkflowContract {
    @Override
    CodeGenWorkflow variant() { return new CodeGenSubgraphWorkflow(); }
}
