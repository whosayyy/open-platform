package com.yin.ai.platform.agent;

import com.yin.ai.platform.agent.executor.NoOpAgentExecutor;
import com.yin.ai.platform.agent.observer.NoOpObserver;
import com.yin.ai.platform.agent.planner.Plan;
import com.yin.ai.platform.agent.rag.AgentRagBridge;
import com.yin.ai.platform.agent.reflector.NoOpReflector;
import com.yin.ai.platform.rag.enhanced.RagRetrievalSnapshot;
import com.yin.ai.platform.rag.enhanced.RetrievedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReActLoopRagSnapshotTest {

    @Test
    void reusesOneSnapshotForPromptAndReferences() {
        AgentRagBridge bridge = mock(AgentRagBridge.class);
        RetrievedDocument document = new RetrievedDocument(
                "d1", "证据", "事实", 9.0, "rerank", Map.of());
        RagRetrievalSnapshot snapshot = new RagRetrievalSnapshot(
                "trace-rag", "问题", "问题", List.of(document),
                "### [E1] 证据\n事实", true, "", 5);
        when(bridge.isAvailable()).thenReturn(true);
        when(bridge.retrieveSnapshot(any(), isNull())).thenReturn(snapshot);

        ReActLoop loop = new ReActLoop(context -> Plan.direct("完成"),
                new NoOpAgentExecutor(), new NoOpObserver(), new NoOpReflector());
        loop.setRagBridge(bridge);
        AgentContext context = AgentContext.from(AgentTask.of("问题"));

        AgentResult result = loop.enter(context);

        assertThat(result.success()).isTrue();
        assertThat(context.getVariable("agent.ragContext")).isEqualTo(snapshot.context());
        assertThat(context.getVariable("agent.ragDocuments", List.class)).containsExactly(document);
        verify(bridge).retrieveSnapshot("问题", null);
        verify(bridge, never()).retrieve(any(), any());
        verify(bridge, never()).retrieveDocuments(any(), any());
    }
}
