package com.yin.ai.platform.core.rag.grounding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimCitationAnalyzerTest {

    @Test
    void measuresCitationsOnEachFactualBlockInsteadOfDocumentLevelPresence() {
        String answer = """
                ## 经营情况

                公司 2025 年营收达到 100 亿元，同比增长 12%。[E1]

                公司在 2026 年宣布进入欧洲市场，并计划新增三座工厂。

                ## 参考资料

                - [E1] 年报
                """;

        var analysis = ClaimCitationAnalyzer.analyze(answer);

        assertThat(analysis.claimCount()).isEqualTo(2);
        assertThat(analysis.supportedClaimCount()).isEqualTo(1);
        assertThat(analysis.coverage()).isEqualTo(0.5);
        assertThat(analysis.unsupportedClaims()).hasSize(1);
    }

    @Test
    void acceptsEvidenceIdsNumberedFootnotesAndSourceLinks() {
        String answer = """
                - 该政策于 2026 年 8 月正式发布。[E1]
                - 官方数据表明同比增长 8%。[2]
                - 监管机构公布了新的实施日期。[公告](https://example.com/notice)
                """;

        var analysis = ClaimCitationAnalyzer.analyze(answer);

        assertThat(analysis.claimCount()).isEqualTo(3);
        assertThat(analysis.coverage()).isEqualTo(1.0);
        assertThat(analysis.meets(0.9)).isTrue();
    }
}
