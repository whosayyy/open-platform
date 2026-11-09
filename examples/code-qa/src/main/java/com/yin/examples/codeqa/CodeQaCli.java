package com.yin.examples.codeqa;

import com.yin.ai.platform.quickstart.Result;

import java.util.List;

/**
 * 命令行入口 —— 真实消费者。
 *
 * <p>用法：
 * <pre>
 * # 离线确定性演示（CI 与验证用）
 * mvn -f examples/code-qa/pom.xml verify
 *
 * # 交互式提问（默认 STUB 模型）
 * java -cp ... com.yin.examples.codeqa.CodeQaCli
 *
 * # 单次提问
 * java -cp ... com.yin.examples.codeqa.CodeQaCli "Agent 的超时是怎么实现的？"
 *
 * # 接真实模型
 * CODE_QA_MODE=live AI_API_KEY=sk-xxx java -cp ... com.yin.examples.codeqa.CodeQaCli "..."
 * </pre>
 *
 * <p>退出码：0 = 全部成功；1 = 有请求失败。verify 阶段依赖这个约定。
 */
public final class CodeQaCli {

    /** 内置演示问题，覆盖「能检索到」与「检索不到」两种路径。 */
    private static final List<String> DEMO_QUESTIONS = List.of(
            "Agent 的引擎超时是怎么实现的？",
            "RAG 的向量检索走的哪个存储？",
            "这个代码库支持 GraphQL 吗？");

    private CodeQaCli() {
    }

    public static void main(String[] args) {
        ModelProviders.Mode mode = ModelProviders.modeFromEnv();
        SnippetStore snippets = DemoKnowledgeBase.build();

        System.out.println("=== wkc-ai-platform 消费者示例：代码库问答助手 ===");
        System.out.println("模型模式: " + mode + "（CODE_QA_MODE=live 可切换真实模型）");
        System.out.println("知识库片段数: " + snippets.size());

        CodeQaAssistant assistant = CodeQaAssistant.create(mode, snippets);
        System.out.println("已注册工具: " + assistant.toolNames());
        System.out.println();

        boolean askingOne = args.length > 0;
        List<String> questions = askingOne ? List.of(String.join(" ", args)) : DEMO_QUESTIONS;

        int failures = 0;
        for (String question : questions) {
            System.out.println("【问】" + question);
            Result result = assistant.ask(question);
            if (result.ok()) {
                System.out.println("【答】" + result.text());
            } else {
                System.out.println("【失败】" + result.error());
                failures++;
            }
            System.out.println();
        }

        if (failures > 0) {
            System.out.println("CONSUMER_EXAMPLE_FAILED: " + failures + " question(s) failed");
            System.exit(1);
        }
        System.out.println("CONSUMER_EXAMPLE_OK: 全部 " + questions.size() + " 个问题成功回答");
    }
}
