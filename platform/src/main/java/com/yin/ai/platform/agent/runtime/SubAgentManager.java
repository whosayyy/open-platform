package com.yin.ai.platform.agent.runtime;

import com.yin.ai.platform.agent.AgentContext;
import com.yin.ai.platform.agent.AgentResult;
import com.yin.ai.platform.agent.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * SubAgent 生命周期管理 — 创建、执行、取消、查询。
 * <p>
 * 通过 {@code Function<AgentContext, AgentResult>} 回调执行 SubAgent 的 ReActLoop，
 * 避免与 DefaultAgentRuntime 的循环依赖。
 */
public class SubAgentManager {

    private static final Logger log = LoggerFactory.getLogger(SubAgentManager.class);

    private final Function<AgentContext, AgentResult> executor;
    private final ConcurrentMap<String, String> parentMap;
    private final ConcurrentMap<String, Set<String>> childMap;

    public SubAgentManager(Function<AgentContext, AgentResult> executor) {
        this.executor = executor;
        this.parentMap = new ConcurrentHashMap<>();
        this.childMap = new ConcurrentHashMap<>();
    }

    /**
     * 创建并执行隔离的 SubAgent。
     *
     * @param parentCtx 父 Agent 上下文
     * @param task      子任务
     * @return SubAgent 执行结果
     * @throws DepthLimitExceededException 超过最大嵌套深度
     */
    public SubAgentResult delegate(AgentContext parentCtx, AgentTask task) {
        int childDepth = parentCtx.getDepth() + 1;
        int maxDepth = parentCtx.getConfig().maxSubAgentDepth();
        if (childDepth > maxDepth) {
            throw new DepthLimitExceededException(parentCtx.getDepth(), childDepth, maxDepth);
        }

        AgentContext childCtx = new AgentContext(parentCtx.getExecCtx(), task, childDepth);

        Set<String> effectiveFilter = task.toolFilter();
        if (effectiveFilter == null) {
            effectiveFilter = parentCtx.getVariable("toolFilter", Set.class);
        }
        if (effectiveFilter != null) {
            childCtx.putVariable("toolFilter", effectiveFilter);
        }

        parentMap.put(childCtx.getAgentId(), parentCtx.getAgentId());
        // 子任务继承父任务的工具过滤器
        childMap.computeIfAbsent(parentCtx.getAgentId(), k -> ConcurrentHashMap.newKeySet())
                .add(childCtx.getAgentId());

        log.info("[SUBAGENT] delegating child={} parent={} depth={} goal=\"{}\"",
                childCtx.getAgentId(), parentCtx.getAgentId(), childDepth, task.goal());

        try {
            AgentResult result = executor.apply(childCtx);
            return SubAgentResult.of(childCtx.getAgentId(), result);
        } catch (Exception e) {
            log.error("[SUBAGENT] child={} failed: {}", childCtx.getAgentId(), e.getMessage(), e);
            return SubAgentResult.empty(childCtx.getAgentId());
        } finally {
            cleanup(childCtx.getAgentId());
        }
    }

    /**
     * 取消指定父 Agent 的所有 SubAgent。
     */
    public void cancelChildren(String parentAgentId) {
        Set<String> children = childMap.getOrDefault(parentAgentId, Collections.emptySet());
        log.info("[SUBAGENT] cancelling {} children of parent={}", children.size(), parentAgentId);
        for (String childId : children) {
            parentMap.remove(childId);
        }
        childMap.remove(parentAgentId);
    }

    /**
     * 查询指定父 Agent 的子 Agent ID 列表。
     */
    public List<String> getChildIds(String parentAgentId) {
        Set<String> children = childMap.get(parentAgentId);
        if (children == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(children);
    }

    /**
     * 查询指定子 Agent 的父 Agent ID。
     */
    public String getParentId(String childAgentId) {
        return parentMap.get(childAgentId);
    }

    int activeChildCount() {
        return parentMap.size();
    }

    private void cleanup(String childId) {
        String parentId = parentMap.remove(childId);
        if (parentId != null) {
            Set<String> siblings = childMap.get(parentId);
            if (siblings != null) {
                siblings.remove(childId);
                if (siblings.isEmpty()) {
                    childMap.remove(parentId);
                }
            }
        }
    }
}
