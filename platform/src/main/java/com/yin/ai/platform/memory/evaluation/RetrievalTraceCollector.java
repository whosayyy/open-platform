package com.yin.ai.platform.memory.evaluation;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 检索溯源收集器 — 内存环形缓冲区，保留最近 N 条检索 Trace。
 */
public class RetrievalTraceCollector {

    private static final int MAX_TRACES = 1000;
    private final ConcurrentLinkedDeque<RetrievalTrace> traces = new ConcurrentLinkedDeque<>();

    public void record(RetrievalTrace trace) {
        traces.addLast(trace);
        while (traces.size() > MAX_TRACES) {
            traces.pollFirst();
        }
    }

    public List<RetrievalTrace> getRecentTraces(int limit) {
        return traces.stream()
                .skip(Math.max(0, traces.size() - limit))
                .toList();
    }

    public List<RetrievalTrace> queryByKnowledgeBase(String kb, int limit) {
        return traces.stream()
                .filter(t -> t.knowledgeBase() != null && t.knowledgeBase().equals(kb))
                .limit(limit)
                .toList();
    }

    public List<RetrievalTrace> queryByDocId(String docId, int limit) {
        return traces.stream()
                .filter(t -> t.retrievedDocs().stream()
                        .anyMatch(d -> d.docId() != null && d.docId().equals(docId)))
                .limit(limit)
                .toList();
    }

    public int size() {
        return traces.size();
    }

    public void clear() {
        traces.clear();
    }
}
