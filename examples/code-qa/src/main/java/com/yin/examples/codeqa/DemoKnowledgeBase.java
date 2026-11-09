package com.yin.examples.codeqa;

/**
 * 示例用的代码片段知识库。
 *
 * <p>内容是真实的平台实现摘要，这样演示输出的答案有实际意义。
 * 真实项目应替换为 RAG / 向量库，而不是这份内存清单。
 */
final class DemoKnowledgeBase {

    private DemoKnowledgeBase() {
    }

    static SnippetStore build() {
        return new SnippetStore()
                .add("engine-timeout",
                        "执行引擎超时",
                        "超时 timeout engine 引擎 卡死 中断",
                        """
                        DefaultExecutionEngine.execute() 把任务提交到独立虚拟线程，
                        调用方用 Future.get(timeout) 等待，超时后 cancel(true) 中断工作线程。

                          FutureTask<T> future = new FutureTask<>(() -> runInContext(ctx, task));
                          Thread.ofVirtual().start(future);
                          T result = future.get(timeoutMs, TimeUnit.MILLISECONDS);

                        超时抛出 TimeoutException，封装为 ExecutionResult.fail()。
                        MDC / SecurityContext 的注入与清理都在工作线程上，不污染调用方。
                        """)
                .add("react-retry",
                        "ReAct 重试上限",
                        "重试 retry react 循环 上限 maxRetriesPerStep 死循环",
                        """
                        runStepLoop 只在「切换到新步骤」时清零重试计数：

                          if (stepIndex != lastExecutedStepIndex) {
                              ctx.resetRetryCount();
                              lastExecutedStepIndex = stepIndex;
                          }

                        RETRY 分支先 incrementRetryCount()，再判断 hasExceededRetries()，
                        超限则推进到下一步，避免同一步骤无限重试。
                        """)
                .add("rag-vector",
                        "RAG 向量检索",
                        "向量 vector rag 检索 es elasticsearch embedding store",
                        """
                        向量检索由 langchain4j-elasticsearch 的 ElasticsearchEmbeddingStore 承担，
                        写入字段为 vector / text / metadata，kNN 查询固定使用 field("vector")。

                        注意：索引 mapping 由 ElasticsearchDocService 创建，
                        当前只声明 docId / title / content / metadata，
                        不含 dense_vector 字段 —— 见示例 README 的"发现的缺口"。
                        """)
                .add("quickstart-facade",
                        "产品门面 Agent",
                        "门面 facade quickstart agent 三行 业务 开发者",
                        """
                        业务开发者只需要两个类型：

                          Agent agent = Agent.of("内容审核")
                                  .prompt("判断是否违规，只回答 PASS 或 REJECT")
                                  .build();
                          Result result = agent.run(article);

                        ExecutionContext、traceId、ModelClient、ToolRegistry
                        全部由门面提供默认值，不需要业务代码关心。
                        """)
                .add("memory-types",
                        "记忆类型",
                        "记忆 memory 类型 type working episodic semantic procedural",
                        """
                        core.memory.MemoryType 定义五种类型：
                        WORKING（任务临时状态）、EPISODIC（时间线事件）、
                        SEMANTIC（事实与关系）、PROCEDURAL（可复用流程），
                        以及已废弃的 SESSION（新写入统一为 EPISODIC + SESSION scope）。
                        """);
    }
}
