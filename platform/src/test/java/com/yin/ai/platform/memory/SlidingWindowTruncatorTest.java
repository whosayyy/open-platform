package com.yin.ai.platform.memory;

import com.yin.ai.platform.core.memory.MemoryCell;
import com.yin.ai.platform.core.memory.MemoryCellId;
import com.yin.ai.platform.core.memory.MemoryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SlidingWindowTruncator")
class SlidingWindowTruncatorTest {

    @Test
    @DisplayName("null 消息列表返回空列表")
    void nullMessages() {
        List<SessionMemoryManager.ChatMessage> result = SlidingWindowTruncator.truncate(null, 100);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("空消息列表返回空列表")
    void emptyMessages() {
        List<SessionMemoryManager.ChatMessage> result = SlidingWindowTruncator.truncate(List.of(), 100);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Token 未超预算时返回全部消息")
    void underBudgetReturnsAll() {
        List<SessionMemoryManager.ChatMessage> messages = List.of(
                new SessionMemoryManager.ChatMessage("user", "Hi"),
                new SessionMemoryManager.ChatMessage("assistant", "Hello!"),
                new SessionMemoryManager.ChatMessage("user", "How are you?"),
                new SessionMemoryManager.ChatMessage("assistant", "I'm good")
        );
        List<SessionMemoryManager.ChatMessage> result = SlidingWindowTruncator.truncate(messages, 10000);
        assertThat(result).hasSize(4);
        assertThat(result.get(0).content()).isEqualTo("Hi");
        assertThat(result.get(3).content()).isEqualTo("I'm good");
    }

    @Test
    @DisplayName("Token 超预算时丢弃最早的完整 turn（user+assistant 对）")
    void overBudgetDropsCompleteTurns() {
        // Turn 1: ~24 tokens, Turn 2: ~2 tokens. Budget 10 → keep only Turn 2
        List<SessionMemoryManager.ChatMessage> messages = List.of(
                new SessionMemoryManager.ChatMessage("user", "AAAAAAAAAAAAAAAAAAAAAAAAAAAA"),    // ~12 tokens
                new SessionMemoryManager.ChatMessage("assistant", "BBBBBBBBBBBBBBBBBBBBBBBBBBBB"), // ~12 tokens
                new SessionMemoryManager.ChatMessage("user", "CC"),   // ~1 token
                new SessionMemoryManager.ChatMessage("assistant", "DD")    // ~1 token
        );
        List<SessionMemoryManager.ChatMessage> result = SlidingWindowTruncator.truncate(messages, 10);
        // Turn 2 = (CC, DD) = ~2 tokens, within budget. Turn 1 would exceed, so dropped.
        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("CC");
        assertThat(result.get(1).content()).isEqualTo("DD");
    }

    @Test
    @DisplayName("截断不会在 turn 中间断开 — 输出始终是完整的 user+assistant 对")
    void overBudgetKeepsTurnIntegrity() {
        // 3 complete turns: (U1,A1)=2t, (U2,A2)=2t, (U3,A3)=2t. Budget=4 → keep last 2 turns.
        List<SessionMemoryManager.ChatMessage> messages = List.of(
                new SessionMemoryManager.ChatMessage("user", "A1"),
                new SessionMemoryManager.ChatMessage("assistant", "B1"),
                new SessionMemoryManager.ChatMessage("user", "A2"),
                new SessionMemoryManager.ChatMessage("assistant", "B2"),
                new SessionMemoryManager.ChatMessage("user", "A3"),
                new SessionMemoryManager.ChatMessage("assistant", "B3")
        );
        List<SessionMemoryManager.ChatMessage> result = SlidingWindowTruncator.truncate(messages, 4);
        // Each pair = 2 tokens. Budget 4 → last 2 complete turns.
        assertThat(result).hasSize(4);
        // Must be complete turns: U2+A2, U3+A3
        assertThat(result.get(0).role()).isEqualTo("user");
        assertThat(result.get(0).content()).isEqualTo("A2");
        assertThat(result.get(1).role()).isEqualTo("assistant");
        assertThat(result.get(1).content()).isEqualTo("B2");
        assertThat(result.get(2).role()).isEqualTo("user");
        assertThat(result.get(2).content()).isEqualTo("A3");
        assertThat(result.get(3).role()).isEqualTo("assistant");
        assertThat(result.get(3).content()).isEqualTo("B3");
    }

    @Test
    @DisplayName("单 turn 超预算时保留该 turn 并记录 WARN 日志")
    void singleTurnExceedsBudgetKeepsAndWarns() {
        List<SessionMemoryManager.ChatMessage> messages = List.of(
                new SessionMemoryManager.ChatMessage("user", "This is a very long message that exceeds the budget"),
                new SessionMemoryManager.ChatMessage("assistant", "short reply")
        );
        List<SessionMemoryManager.ChatMessage> result = SlidingWindowTruncator.truncate(messages, 2);
        // Single turn exceeds budget but we keep it (can't return empty context)
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("截断后保持时间顺序")
    void preservesTimeOrder() {
        List<SessionMemoryManager.ChatMessage> messages = List.of(
                new SessionMemoryManager.ChatMessage("user", "AAAA"),
                new SessionMemoryManager.ChatMessage("assistant", "BBBB"),
                new SessionMemoryManager.ChatMessage("user", "CCCC"),
                new SessionMemoryManager.ChatMessage("assistant", "DDDD")
        );
        List<SessionMemoryManager.ChatMessage> result = SlidingWindowTruncator.truncate(messages, 100);
        for (int i = 1; i < result.size(); i++) {
            int origIdxI = messages.indexOf(result.get(i));
            int origIdxPrev = messages.indexOf(result.get(i - 1));
            assertThat(origIdxI).isGreaterThan(origIdxPrev);
        }
    }

    @Test
    @DisplayName("奇数消息（末尾 solo user）被正确保留")
    void oddMessageCountSoloUserAtEnd() {
        // 2 complete turns + solo user at end (odd count = 5)
        List<SessionMemoryManager.ChatMessage> messages = List.of(
                new SessionMemoryManager.ChatMessage("user", "A1"),
                new SessionMemoryManager.ChatMessage("assistant", "B1"),   // Turn 1
                new SessionMemoryManager.ChatMessage("user", "A2"),
                new SessionMemoryManager.ChatMessage("assistant", "B2"),   // Turn 2
                new SessionMemoryManager.ChatMessage("user", "A3")         // Solo (no reply yet)
        );
        List<SessionMemoryManager.ChatMessage> result = SlidingWindowTruncator.truncate(messages, 100);
        // All within budget → all 5 messages kept, solo user at the end
        assertThat(result).hasSize(5);
        assertThat(result.get(4).role()).isEqualTo("user");
        assertThat(result.get(4).content()).isEqualTo("A3");
    }

    @Test
    @DisplayName("多个完整 turn 全部在预算内时返回全部")
    void multipleCompleteTurnsUnderBudget() {
        List<SessionMemoryManager.ChatMessage> messages = List.of(
                new SessionMemoryManager.ChatMessage("user", "Q1"),
                new SessionMemoryManager.ChatMessage("assistant", "R1"),
                new SessionMemoryManager.ChatMessage("user", "Q2"),
                new SessionMemoryManager.ChatMessage("assistant", "R2"),
                new SessionMemoryManager.ChatMessage("user", "Q3"),
                new SessionMemoryManager.ChatMessage("assistant", "R3"),
                new SessionMemoryManager.ChatMessage("user", "Q4"),
                new SessionMemoryManager.ChatMessage("assistant", "R4")
        );
        List<SessionMemoryManager.ChatMessage> result = SlidingWindowTruncator.truncate(messages, 10000);
        assertThat(result).hasSize(8);
    }

    // ==================== MemoryCell API tests ====================

    @Test
    @DisplayName("truncateCells: null 列表返回空列表")
    void truncateCellsNullReturnsEmpty() {
        assertThat(SlidingWindowTruncator.truncateCells(null, 100)).isEmpty();
    }

    @Test
    @DisplayName("truncateCells: 空列表返回空列表")
    void truncateCellsEmptyReturnsEmpty() {
        assertThat(SlidingWindowTruncator.truncateCells(List.of(), 100)).isEmpty();
    }

    @Test
    @DisplayName("truncateCells: Token 未超预算时返回全部")
    void truncateCellsUnderBudgetReturnsAll() {
        List<MemoryCell> cells = List.of(
                userCell("Hi"),
                assistantCell("Hello!"),
                userCell("How are you?"),
                assistantCell("I'm good"));

        List<MemoryCell> result = SlidingWindowTruncator.truncateCells(cells, 10000);
        assertThat(result).hasSize(4);
    }

    @Test
    @DisplayName("truncateCells: 超预算时丢弃最早的完整 turn")
    void truncateCellsOverBudgetDropsTurns() {
        String longText = "A".repeat(100);
        List<MemoryCell> cells = List.of(
                userCell(longText),
                assistantCell(longText),    // Turn 1: ~80 tokens
                userCell("CC"),             // Turn 2: ~1 token
                assistantCell("DD"));       // Turn 2: ~1 token

        List<MemoryCell> result = SlidingWindowTruncator.truncateCells(cells, 10);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("CC");
        assertThat(result.get(1).content()).isEqualTo("DD");
    }

    @Test
    @DisplayName("truncateCells: 保持 turn 完整性")
    void truncateCellsKeepsTurnIntegrity() {
        List<MemoryCell> cells = List.of(
                userCell("A1"), assistantCell("B1"),
                userCell("A2"), assistantCell("B2"),
                userCell("A3"), assistantCell("B3"));

        List<MemoryCell> result = SlidingWindowTruncator.truncateCells(cells, 4);
        assertThat(result).hasSize(4);
        assertThat(roleOf(result.get(0))).isEqualTo("user");
        assertThat(roleOf(result.get(1))).isEqualTo("assistant");
        assertThat(roleOf(result.get(2))).isEqualTo("user");
        assertThat(roleOf(result.get(3))).isEqualTo("assistant");
    }

    @Test
    @DisplayName("truncateCells: 保留顺序")
    void truncateCellsPreservesOrder() {
        List<MemoryCell> cells = List.of(
                userCell("AAAA"), assistantCell("BBBB"),
                userCell("CCCC"), assistantCell("DDDD"));

        List<MemoryCell> result = SlidingWindowTruncator.truncateCells(cells, 100);
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i).timestamp())
                    .isAfterOrEqualTo(result.get(i - 1).timestamp());
        }
    }

    @Test
    @DisplayName("truncateCells: 末尾 solo user 被保留")
    void truncateCellsKeepsSoloUserAtEnd() {
        List<MemoryCell> cells = List.of(
                userCell("A1"), assistantCell("B1"),
                userCell("A2"), assistantCell("B2"),
                userCell("A3"));

        List<MemoryCell> result = SlidingWindowTruncator.truncateCells(cells, 100);
        assertThat(result).hasSize(5);
        assertThat(roleOf(result.get(4))).isEqualTo("user");
    }

    private static MemoryCell userCell(String content) {
        return MemoryCell.of(MemoryCellId.of(java.util.UUID.randomUUID().toString()),
                MemoryType.SESSION, content,
                Map.of("role", "user"));
    }

    private static MemoryCell assistantCell(String content) {
        return MemoryCell.of(MemoryCellId.of(java.util.UUID.randomUUID().toString()),
                MemoryType.SESSION, content,
                Map.of("role", "assistant"));
    }

    private static String roleOf(MemoryCell cell) {
        return (String) cell.attributes().getOrDefault("role", "user");
    }
}
