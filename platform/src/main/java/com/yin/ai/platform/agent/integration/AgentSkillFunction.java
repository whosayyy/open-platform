package com.yin.ai.platform.agent.integration;

import com.yin.ai.platform.function.AbstractFunction;
import com.yin.ai.platform.function.FunctionDefinition;
import com.yin.ai.platform.function.FunctionDefinition.ParamDef;
import com.yin.ai.platform.function.FunctionDefinition.ParamType;
import com.yin.ai.platform.function.FunctionInput;
import com.yin.ai.platform.function.FunctionResult;
import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.core.skill.SkillManifest;
import com.yin.ai.platform.runtime.SkillExecutionContext;
import com.yin.ai.platform.runtime.SkillResult;
import com.yin.ai.platform.runtime.SkillRuntime;

import java.util.List;

/**
 * 将 Skill 包装为 Function，使 Agent 可通过 FunctionRegistry 透明调用 Skill。
 * <p>
 * 命名约定: Function 名 = "skill:" + skillName（如 "skill:summary"）
 * 输入参数映射:
 * <ul>
 *   <li>"userInput" → SkillExecutionContext.userInput</li>
 *   <li>"sessionId"  → SkillExecutionContext.sessionId（可选）</li>
 * </ul>
 */
public class AgentSkillFunction extends AbstractFunction {

    private final String skillName;
    private final SkillRuntime skillRuntime;
    private final SkillLoader skillLoader;

    public AgentSkillFunction(String skillName, SkillRuntime skillRuntime, SkillLoader skillLoader) {
        this.skillName = skillName;
        this.skillRuntime = skillRuntime;
        this.skillLoader = skillLoader;
    }

    public String getSkillName() {
        return skillName;
    }

    @Override
    public FunctionDefinition getDefinition() {
        SkillManifest manifest = skillLoader.load(skillName).orElse(null);
        String desc = manifest != null ? manifest.getDescription() : "Execute skill: " + skillName;
        return FunctionDefinition.builder()
                .name("skill:" + skillName)
                .description(desc)
                .category("skill")
                .risk(FunctionDefinition.Risk.WRITE)
                .callableByLLM(true)
                .parameters(List.of(
                        ParamDef.builder()
                                .name("userInput")
                                .type(ParamType.STRING)
                                .required(true)
                                .description("用户输入")
                                .build()))
                .build();
    }

    @Override
    protected FunctionResult doExecute(FunctionInput input) {
        String userInput = (String) input.getParameters().get("userInput");
        if (userInput == null || userInput.isBlank()) {
            return FunctionResult.fail("SKILL_MISSING_INPUT", "userInput is required");
        }

        SkillExecutionContext.SkillExecutionContextBuilder builder = SkillExecutionContext.builder()
                .userInput(userInput);

        if (input.getParameters().containsKey("sessionId")) {
            builder.sessionId((String) input.getParameters().get("sessionId"));
        }
        if (input.getTraceId() != null) {
            builder.traceId(input.getTraceId());
        }

        SkillExecutionContext ctx = builder.build();
        SkillResult result = skillRuntime.execute(skillName, ctx);

        if (result.isSuccess()) {
            return FunctionResult.ok(result.getData());
        }
        return FunctionResult.fail("SKILL_ERROR",
                result.getError() != null ? result.getError().getMessage() : "unknown");
    }
}
