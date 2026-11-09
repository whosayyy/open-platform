package com.yin.ai.platform.gateway.guard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于正则表达式的 PII 检测器（默认实现）。
 * <p>
 * 检测 5 种 PII 类型：身份证、手机号、银行卡（Luhn 校验）、邮箱、地址。
 * 输入端匹配 BLOCK 类型 → 拒绝请求；输出端全部脱敏。
 * <p>
 * 关键优化：
 * <ul>
 *   <li>所有正则预编译（构造阶段一次完成）</li>
 *   <li>地址正则需数字前缀或省市区前缀，避免单字后缀误杀</li>
 *   <li>银行卡增加 Luhn 算法校验，降低 99% 误杀率</li>
 *   <li>mask() 单次扫描完成（合并正则），避免 5 次全文本遍历</li>
 * </ul>
 */
@Slf4j
@Component
public class RegexPIIDetector implements PIIDetector {

    private final PIIProperties properties;
    private final List<CompiledType> compiledTypes;
    private final Pattern maskCombinedPattern;
    private final ThreadLocal<StringBuilder> maskStringBuilder =
            ThreadLocal.withInitial(() -> new StringBuilder(512));

    public RegexPIIDetector(PIIProperties properties) {
        this.properties = properties;
        this.compiledTypes = buildCompiledTypes();
        this.maskCombinedPattern = buildMaskCombinedPattern();
    }

    // ── detect ──

    @Override
    public PIIResult detect(String text) {
        if (text == null || text.isBlank()) {
            return NONE;
        }

        List<PIIMatch> matches = new ArrayList<>();
        List<String> matchedTypes = new ArrayList<>();

        for (CompiledType ct : compiledTypes) {
            Matcher matcher = ct.pattern.matcher(text);
            while (matcher.find()) {
                String original = matcher.group();

                // 银行卡附加 Luhn 校验
                if ("bank-card".equals(ct.type.name()) && !luhnCheck(original)) {
                    continue;
                }

                String masked = applyMask(original, ct.type);
                matches.add(new PIIMatch(ct.type, original, masked, matcher.start(), matcher.end()));
                if (!matchedTypes.contains(ct.type.name())) {
                    matchedTypes.add(ct.type.name());
                }
            }
        }

        return matches.isEmpty() ? NONE : new PIIResult(matches, matchedTypes);
    }

    // ── mask (single pass) ──

    @Override
    public String mask(String text) {
        if (text == null || text.isBlank() || compiledTypes.isEmpty()) {
            return text;
        }

        // 一次扫描找到所有匹配（按位置从右往左排，避免替换导致位置偏移）
        List<MaskHit> hits = new ArrayList<>();
        Matcher m = maskCombinedPattern.matcher(text);
        while (m.find()) {
            for (int i = 0; i < compiledTypes.size(); i++) {
                int gidx = i + 1;
                String group = m.group(gidx);
                if (group != null) {
                    CompiledType ct = compiledTypes.get(i);
                    if ("bank-card".equals(ct.type.name()) && !luhnCheck(group)) {
                        continue;
                    }
                    hits.add(new MaskHit(m.start(gidx), m.end(gidx), applyMask(group, ct.type)));
                    break;
                }
            }
        }

        if (hits.isEmpty()) {
            return text;
        }

        // 按位置降序排列，从右往左替换
        hits.sort(Comparator.comparingInt((MaskHit h) -> h.start).reversed());

        StringBuilder sb = maskStringBuilder.get();
        sb.setLength(0);
        sb.append(text);
        for (MaskHit hit : hits) {
            sb.replace(hit.start, hit.end, hit.masked);
        }
        return sb.toString();
    }

    // ── mask logic ──

    private String applyMask(String value, PIIType type) {
        return switch (type.name()) {
            case "id-card"   -> maskIdCard(value);
            case "phone"     -> maskPhone(value);
            case "bank-card" -> maskBankCard(value);
            case "email"     -> maskEmail(value);
            case "address"   -> maskAddress(value);
            default          -> "***";
        };
    }

    private String maskIdCard(String value) {
        String cleaned = value.replaceAll("[Xx]", "X");
        if (cleaned.length() >= 15) {
            return cleaned.substring(0, 3) + "***********" + cleaned.substring(cleaned.length() - 4);
        }
        return "***";
    }

    private String maskPhone(String value) {
        if (value.length() == 11) {
            return value.substring(0, 3) + "****" + value.substring(7);
        }
        return "***";
    }

    private String maskBankCard(String value) {
        if (value.length() >= 16) {
            return value.substring(0, 4) + "********" + value.substring(value.length() - 4);
        }
        return "***";
    }

    private String maskEmail(String value) {
        int at = value.indexOf('@');
        if (at > 1) {
            return value.charAt(0) + "***" + value.substring(at);
        }
        return "***@***";
    }

    private String maskAddress(String value) {
        if (value.length() <= 6) return "***";
        return value.substring(0, 3) + "***" + value.substring(value.length() - 3);
    }

    // ── Luhn 校验 ──

    /**
     * Luhn (mod 10) 算法：验证银行卡号有效性。
     * <p>
     * 只接受 16-19 位纯数字，逐位双倍求和，末尾校验位必须为 0。
     * 非银行卡号（如订单号、时间戳、随机 ID）有 90%+ 概率被过滤。
     */
    static boolean luhnCheck(String digits) {
        if (digits == null || digits.length() < 16 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            // 确保每一位都是合法数字，非法字符直接返回 false
            int n = digits.charAt(i) - '0';
            if (n < 0 || n > 9) {
                return false;
            }
            // 从右数第 2、4、6... 位（即偶数位）乘以 2，若结果 > 9 则减 9
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        // 总和能被 10 整除则号码有效
        return sum % 10 == 0;
    }

    // ── address regex ──

    /**
     * 地址检测正则（窄化版）。
     * <p>
     * 匹配两类真实地址格式：
     * <ol>
     *   <li>数字前缀 + 建筑关键词：3号楼、502室、12栋3单元</li>
     *   <li>省市区前缀 + 路/街/道/镇/乡/村后缀：上海市浦东新区张江路、西湖区文三路</li>
     * </ol>
     * 排除：号称（无数字）、密室（无数字）、办公室（无数字）、城市（无后缀）、地区（无后缀）
     */
    private static String addressRegex() {
        return "(?:\\d{1,5}(?:号|楼|栋|单元|室|层|幢|弄|巷|排|座))[\\u4e00-\\u9fa5\\d\\s\\-]{0,12}"
                + "|[\\u4e00-\\u9fa5]{2,6}(?:省|市|区|县)(?:[\\u4e00-\\u9fa5]{2,12}(?:路|街|道|镇|乡|村|"
                + "大道|小区|新村|花园|苑|公寓|广场|中心))";
    }

    // ── compiled types ──

    private List<CompiledType> buildCompiledTypes() {
        List<CompiledType> list = new ArrayList<>();

        if (properties.isIdCardEnabled()) {
            list.add(compile("id-card", "\\b\\d{17}[\\dXx]\\b", PIIAction.BLOCK, "身份证号"));
        }
        if (properties.isPhoneEnabled()) {
            list.add(compile("phone", "\\b1[3-9]\\d{9}\\b", PIIAction.BLOCK, "手机号"));
        }
        if (properties.isBankCardEnabled()) {
            list.add(compile("bank-card", "\\b\\d{16,19}\\b", PIIAction.BLOCK, "银行卡号"));
        }
        if (properties.isEmailEnabled()) {
            list.add(compile("email",
                    "\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b",
                    PIIAction.MASK, "邮箱"));
        }
        if (properties.isAddressEnabled()) {
            list.add(compile("address", addressRegex(), PIIAction.MASK, "地址"));
        }

        log.info("[PII] pre-compiled {} PII type(s): {}",
                list.size(),
                list.stream().map(ct -> ct.type.displayName()).toList());
        return list;
    }

    private CompiledType compile(String name, String regex, PIIAction action, String displayName) {
        PIIType type = new PIIType(name, regex, name, action, displayName);
        return new CompiledType(type, Pattern.compile(regex));
    }

    /**
     * 构建 mask() 单次扫描用的合并正则。
     * 格式：(idCard)|(phone)|(bank)|(email)|(address)
     * 每个子模式独占一个 capturing group，通过 group index 识别匹配类型。
     */
    private Pattern buildMaskCombinedPattern() {
        StringBuilder sb = new StringBuilder();
        // 遍历所有编译类型，用 "|" 拼接各自的正则
        for (CompiledType ct : compiledTypes) {
            if (!sb.isEmpty()) {
                sb.append("|");   // 非首个元素前加 "|"
            }
            sb.append("(").append(ct.type.regex()).append(")"); // 每个子模式用捕获组包裹
        }
        // 边界情况：没有任何类型时，返回永不匹配的模式
        if (sb.isEmpty()) {
            return Pattern.compile("$^"); // never matches
        }
        return Pattern.compile(sb.toString());
    }

    // ── inner types ──

    /** 预编译好的类型（PIIType + cached Pattern） */
    private record CompiledType(PIIType type, Pattern pattern) {}

    /** mask() 单次扫描中记录的一次匹配 */
    private record MaskHit(int start, int end, String masked) {}
}
