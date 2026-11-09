package com.yin.ai.platform.gateway.guard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RegexPIIDetector")
class RegexPIIDetectorTest {

    private PIIProperties properties;
    private RegexPIIDetector detector;

    @BeforeEach
    void setUp() {
        properties = new PIIProperties();
        detector = new RegexPIIDetector(properties);
    }

    @Test
    @DisplayName("检测身份证号并拦截")
    void shouldDetectIdCard() {
        String input = "我的身份证号是310101199001011234，请帮我查询";
        PIIDetector.PIIResult result = detector.detect(input);

        assertThat(result.blocked()).isTrue();
        assertThat(result.matchedTypes()).contains("id-card");
        // 18 位身份证号也匹配银行卡（16+ 位）和地址规则，属正常重叠
        assertThat(result.matches()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(result.matches().stream().anyMatch(m -> m.original().equals("310101199001011234"))).isTrue();
    }

    @Test
    @DisplayName("检测手机号并拦截")
    void shouldDetectPhone() {
        String input = "联系电话：13812341234，有问题请致电";
        PIIDetector.PIIResult result = detector.detect(input);

        assertThat(result.blocked()).isTrue();
        assertThat(result.matchedTypes()).contains("phone");
        assertThat(result.matches().get(0).original()).isEqualTo("13812341234");
    }

    @Test
    @DisplayName("检测银行卡号并拦截")
    void shouldDetectBankCard() {
        String input = "转账到6222021234567894账户";
        PIIDetector.PIIResult result = detector.detect(input);

        assertThat(result.blocked()).isTrue();
        assertThat(result.matchedTypes()).contains("bank-card");
    }

    @Test
    @DisplayName("检测邮箱并脱敏（不拦截）")
    void shouldDetectEmailAndMask() {
        String input = "我的邮箱是user@example.com，请联系我";
        PIIDetector.PIIResult result = detector.detect(input);

        assertThat(result.detected()).isTrue();
        assertThat(result.matchedTypes()).contains("email");
        // 邮箱是 MASK 级别，不 BLOCK
        assertThat(result.blocked()).isFalse();

        // 验证脱敏
        String masked = detector.mask(input);
        assertThat(masked).doesNotContain("user@example.com");
        assertThat(masked).contains("***");
        assertThat(masked).contains("@example.com");
    }

    @Test
    @DisplayName("混合 PII 全部检测")
    void shouldDetectMixedPII() {
        String input = "用户信息：张三，身份证310101199001011234，手机13812341234，"
                + "银行卡6222021234567894，邮箱user@example.com";

        PIIDetector.PIIResult result = detector.detect(input);

        assertThat(result.blocked()).isTrue();
        assertThat(result.matchedTypes()).contains("id-card", "phone", "bank-card", "email");
        assertThat(result.matches()).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("正常文本无 PII")
    void shouldNotDetectPIIInNormalText() {
        String input = "今天天气不错，适合出去散步，推荐去公园放风筝";
        PIIDetector.PIIResult result = detector.detect(input);

        assertThat(result.detected()).isFalse();
        assertThat(result.matches()).isEmpty();
    }

    @Test
    @DisplayName("空输入返回 NONE")
    void shouldReturnNoneForEmptyInput() {
        PIIDetector.PIIResult result = detector.detect("");
        assertThat(result).isEqualTo(PIIDetector.NONE);

        result = detector.detect(null);
        assertThat(result).isEqualTo(PIIDetector.NONE);
    }

    @Test
    @DisplayName("脱敏：手机号格式 138****1234")
    void shouldMaskPhoneInCorrectFormat() {
        String masked = detector.mask("我的手机是13812341234");
        assertThat(masked).contains("138****1234");
        assertThat(masked).doesNotContain("13812341234");
    }

    @Test
    @DisplayName("脱敏：身份证保留首3尾4")
    void shouldMaskIdCardCorrectly() {
        String masked = detector.mask("ID:310101199001011234");
        assertThat(masked).contains("310***********1234");
        assertThat(masked).doesNotContain("310101199001011234");
    }

    @Test
    @DisplayName("脱敏：银行卡保留首4尾4")
    void shouldMaskBankCardCorrectly() {
        String masked = detector.mask("CARD:6222021234567894");
        assertThat(masked).contains("6222********7894");
        assertThat(masked).doesNotContain("6222021234567894");
    }

    @Test
    @DisplayName("16 位非银行卡号（不通过 Luhn）不被误检测")
    void shouldHandle16DigitNumber() {
        // 16 位订单号不通过 Luhn 校验，不应被误判为银行卡
        String input = "订单编号：2024060112000123";
        PIIDetector.PIIResult result = detector.detect(input);

        // Luhn 过滤后不应被检测为银行卡
        assertThat(result.matchedTypes()).doesNotContain("bank-card");
    }

    @Test
    @DisplayName("关闭特定类型后不再检测")
    void shouldSkipDisabledType() {
        properties.setPhoneEnabled(false);
        RegexPIIDetector disabledDetector = new RegexPIIDetector(properties);

        PIIDetector.PIIResult result = disabledDetector.detect("手机号13812341234");
        assertThat(result.matchedTypes()).doesNotContain("phone");
    }

    @Test
    @DisplayName("脱敏不会影响不含 PII 的文本")
    void shouldNotAffectTextWithoutPII() {
        String text = "这是一段没有敏感信息的普通文本";
        String masked = detector.mask(text);
        assertThat(masked).isEqualTo(text);
    }
}
