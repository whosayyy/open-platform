package com.yin.ai.platform.agent.pattern.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yin.ai.platform.agent.pattern.AgentExecutionContext;
import com.yin.ai.platform.agent.pattern.TaskStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认研究综合政策 — 从已完成 web_search 步骤解析来源，并在综合后清洗未验证 URL。
 */
public class DefaultResearchSynthesisPolicy implements ResearchSynthesisPolicy {

    private static final Logger log = LoggerFactory.getLogger(DefaultResearchSynthesisPolicy.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)https?://[^\\s<>()]+");

    @Override
    public List<SourceRef> collectSources(AgentExecutionContext ctx) {
        List<SourceRef> sources = new ArrayList<>();
        if (ctx.getPlan() == null) {
            return sources;
        }
        for (TaskStep step : ctx.getPlan().steps()) {
            if (!step.isCompleted() || step.output() == null) {
                continue;
            }
            if (step.executor() == null || !step.executor().startsWith("web_search")) {
                continue;
            }
            sources.addAll(parseResults(step.output()));
        }
        return sources;
    }

    private List<SourceRef> parseResults(String output) {
        List<SourceRef> refs = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(output);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                return refs;
            }
            for (JsonNode item : results) {
                String url = item.path("url").asText("").trim();
                if (url.isBlank()) {
                    continue;
                }
                refs.add(new SourceRef(
                        item.path("title").asText(""),
                        stripTrailingPunctuation(url),
                        item.path("source").asText(""),
                        item.path("snippet").asText(item.path("content").asText("")),
                        item.path("publishedDate").asText(item.path("published_date").asText(""))));
            }
        } catch (Exception e) {
            log.debug("[SYNTH-POLICY] parse web_search output failed: {}", e.getMessage());
        }
        return refs;
    }

    @Override
    public String formatSources(List<SourceRef> sources) {
        if (sources == null || sources.isEmpty()) {
            return "（无已验证来源，只能基于研究发现组织内容，不得编造来源链接）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            SourceRef ref = sources.get(i);
            String title = ref.title().isBlank() ? ref.url() : ref.title();
            sb.append(i + 1).append(". ").append(title);
            if (!ref.source().isBlank()) {
                sb.append("（").append(ref.source()).append("）");
            }
            sb.append(" — ").append(ref.url()).append("\n");
        }
        return sb.toString().trim();
    }

    @Override
    public Verdict verify(String report, List<SourceRef> sources) {
        if (report == null || report.isBlank()) {
            return new Verdict(true, report, List.of());
        }

        Set<String> verified = new LinkedHashSet<>();
        for (SourceRef ref : sources) {
            if (ref.url() != null && !ref.url().isBlank()) {
                verified.add(normalizeUrl(ref.url()));
            }
        }

        List<String> foreign = new ArrayList<>();
        StringBuilder sanitized = new StringBuilder();
        Matcher matcher = URL_PATTERN.matcher(report);
        int last = 0;
        while (matcher.find()) {
            String matched = matcher.group();
            String url = stripTrailingPunctuation(matched);
            sanitized.append(report, last, matcher.start());
            if (verified.contains(normalizeUrl(url))) {
                sanitized.append(matched);
            } else {
                foreign.add(url);
                log.warn("[SYNTH-POLICY] report cites unverified url: {}", url);
                sanitized.append("[未验证来源]");
            }
            last = matcher.end();
        }
        sanitized.append(report.substring(last));

        return new Verdict(foreign.isEmpty(), sanitized.toString(), foreign);
    }

    private String normalizeUrl(String url) {
        return stripTrailingPunctuation(url).toLowerCase(Locale.ROOT);
    }

    private String stripTrailingPunctuation(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        while (!s.isEmpty()) {
            char c = s.charAt(s.length() - 1);
            if (Character.isLetterOrDigit(c) || c == '/' || c == '-' || c == '_'
                    || c == '?' || c == '=' || c == '&' || c == '%' || c == '#' || c == '+') {
                break;
            }
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
