package com.yin.ai.platform.core.agent.research;

import com.yin.ai.platform.core.agent.plan.AgentPlan;
import com.yin.ai.platform.core.agent.plan.PlanStep;
import com.yin.ai.platform.core.agent.plan.PlanStepStatus;
import com.yin.ai.platform.core.json.PortableJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dependency-free source whitelist and report citation policy. */
public final class DefaultResearchSynthesisPolicy implements ResearchSynthesisPolicy {

    private static final Pattern URL_PATTERN = Pattern.compile("(?i)https?://[^\\s<>()]+");

    public static DefaultResearchSynthesisPolicy standard() {
        return new DefaultResearchSynthesisPolicy();
    }

    @Override
    public List<ResearchSource> collectSources(AgentPlan plan) {
        if (plan == null) return List.of();
        Map<String, ResearchSource> sources = new LinkedHashMap<>();
        for (PlanStep step : plan.steps()) {
            if (step.status() != PlanStepStatus.COMPLETED
                    || step.output() == null || step.output().isBlank()) {
                continue;
            }
            if (step.executor() == null || step.executor().isBlank()
                    || "llm".equalsIgnoreCase(step.executor())) {
                continue;
            }
            collectStructured(step.output(), sources);
            collectUrls(step.output(), sources);
        }
        return List.copyOf(sources.values());
    }

    @Override
    public String formatSources(List<ResearchSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return "No verified source URLs were present in the execution evidence. "
                    + "Do not invent a source or URL.";
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < sources.size(); index++) {
            ResearchSource source = sources.get(index);
            String label = source.title().isBlank() ? source.url() : source.title();
            result.append(index + 1).append(". ").append(label);
            if (!source.source().isBlank()) {
                result.append(" (").append(source.source()).append(')');
            }
            result.append(" - ").append(source.url()).append('\n');
        }
        return result.toString().trim();
    }

    @Override
    public Verification verify(String report, List<ResearchSource> sources) {
        if (report == null || report.isBlank()) {
            return new Verification(true, report, List.of());
        }
        Set<String> verified = new LinkedHashSet<>();
        if (sources != null) {
            sources.forEach(source -> verified.add(normalizeUrl(source.url())));
        }
        Matcher matcher = URL_PATTERN.matcher(report);
        StringBuilder sanitized = new StringBuilder();
        List<String> unverified = new ArrayList<>();
        int cursor = 0;
        while (matcher.find()) {
            String raw = matcher.group();
            String url = stripTrailingPunctuation(raw);
            sanitized.append(report, cursor, matcher.start());
            if (verified.contains(normalizeUrl(url))) {
                sanitized.append(raw);
            } else {
                unverified.add(url);
                sanitized.append("[unverified source removed]");
                if (raw.length() > url.length()) sanitized.append(raw.substring(url.length()));
            }
            cursor = matcher.end();
        }
        sanitized.append(report.substring(cursor));
        return new Verification(unverified.isEmpty(), sanitized.toString(), unverified);
    }

    private static void collectStructured(String output,
                                          Map<String, ResearchSource> sources) {
        try {
            Object document = PortableJson.parseDocument(output);
            if (document instanceof Map<?, ?> map) {
                Object candidates = first(map, "results", "documents", "sources", "evidence");
                if (candidates instanceof Iterable<?> values) {
                    values.forEach(value -> addStructured(value, sources));
                } else {
                    addStructured(map, sources);
                }
            } else if (document instanceof Iterable<?> values) {
                values.forEach(value -> addStructured(value, sources));
            }
        } catch (RuntimeException ignored) {
            // Free-form tool output is handled by URL scanning below.
        }
    }

    private static void addStructured(Object raw, Map<String, ResearchSource> sources) {
        if (!(raw instanceof Map<?, ?> map)) return;
        Map<?, ?> metadata = map.get("metadata") instanceof Map<?, ?> value ? value : Map.of();
        String url = firstText(map, "url", "sourceUrl", "link");
        if (url == null || url.isBlank()) {
            url = firstText(metadata, "url", "sourceUrl", "link");
        }
        url = stripTrailingPunctuation(url);
        if (!isHttpUrl(url)) return;
        ResearchSource source = new ResearchSource(
                firstText(map, "title", "name"), url,
                firstText(map, "source", "provider"),
                firstText(map, "snippet", "content", "text"),
                firstText(map, "publishedAt", "publishedDate", "published_date"));
        sources.putIfAbsent(normalizeUrl(url), source);
    }

    private static void collectUrls(String output, Map<String, ResearchSource> sources) {
        Matcher matcher = URL_PATTERN.matcher(output);
        while (matcher.find()) {
            String url = stripTrailingPunctuation(matcher.group());
            if (isHttpUrl(url)) {
                sources.putIfAbsent(normalizeUrl(url),
                        new ResearchSource("", url, "", "", ""));
            }
        }
    }

    private static Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String firstText(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !value.toString().isBlank()) return value.toString().trim();
        }
        return "";
    }

    private static boolean isHttpUrl(String value) {
        return value != null && (value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8));
    }

    private static String normalizeUrl(String value) {
        String normalized = stripTrailingPunctuation(value).toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/") && normalized.length() > "https://x".length()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String stripTrailingPunctuation(String value) {
        if (value == null) return "";
        String result = value.trim();
        while (!result.isEmpty()) {
            char current = result.charAt(result.length() - 1);
            if (Character.isLetterOrDigit(current) || current == '/' || current == '-'
                    || current == '_' || current == '?' || current == '=' || current == '&'
                    || current == '%' || current == '#' || current == '+') {
                break;
            }
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
