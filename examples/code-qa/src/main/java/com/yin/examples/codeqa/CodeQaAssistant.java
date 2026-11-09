package com.yin.examples.codeqa;

import com.yin.ai.platform.core.tool.DefaultToolRegistry;
import com.yin.ai.platform.core.tool.ToolRegistry;
import com.yin.ai.platform.quickstart.Agent;
import com.yin.ai.platform.quickstart.ModelClients;
import com.yin.ai.platform.quickstart.Result;

import java.util.List;

/**
 * 「代码库问答助手」—— wkc-ai-platform 的一个真实消费者。
 *
 * <p>它与新闻业务没有任何关系：这是验证平台抽象是否成立的第二个领域。
 * 整个助手只依赖平台的 {@code quickstart} 与 {@code core.tool} 两个包，
 * 不依赖 Spring、不依赖 MyBatis、不依赖任何业务模块。
 *
 * <p>业务代码的总量就是这个类本身：一个 prompt、一个工具、一次调用。
 */
public final class CodeQaAssistant {

    /** 业务提示词：告诉 Agent 它是谁、要干什么、工具怎么用。 */
    private static final String SYSTEM_PROMPT = """
            你是一个代码库问答助手。回答开发者关于本代码库的问题。

            规则：
            1. 回答任何关于代码的问题之前，必须先调用 search_code 工具检索代码片段。
            2. 只根据检索到的代码片段回答，不要编造不存在的类或方法。
            3. 检索结果为空时，明确说明"代码库中没有找到相关内容"，不要猜测。
            4. 回答用中文，先给结论，再给依据。
            """;

    private final SnippetStore store;
    private final ToolRegistry toolRegistry;
    private final Agent agent;

    private CodeQaAssistant(SnippetStore store, ToolRegistry toolRegistry, Agent agent) {
        this.store = store;
        this.toolRegistry = toolRegistry;
        this.agent = agent;
    }

    /**
     * 构建助手。
     *
     * @param mode     STUB（离线、确定性、CI 用）或 LIVE（真实模型服务）
     * @param snippets 代码片段知识库
     */
    public static CodeQaAssistant create(ModelProviders.Mode mode, SnippetStore snippets) {
        ToolRegistry tools = new DefaultToolRegistry();
        tools.register(snippets.asTool());

        // 消费方唯一需要做的基础设施接线：把模型客户端交给门面。
        ModelClients.register(ModelProviders.create(mode, snippets));

        Agent agent = Agent.of("代码库问答")
                .prompt(SYSTEM_PROMPT)
                .tools(tools)
                .build();

        return new CodeQaAssistant(snippets, tools, agent);
    }

    /** 问一个问题，拿回一个 {@link Result}。 */
    public Result ask(String question) {
        return agent.run(question);
    }

    /** 已注册的工具名，便于示例输出与断言。 */
    public List<String> toolNames() {
        return toolRegistry.definitions().stream()
                .map(com.yin.ai.platform.core.tool.ToolDefinition::name)
                .sorted()
                .toList();
    }

    /** 知识库规模。 */
    public int snippetCount() {
        return store.size();
    }
}
