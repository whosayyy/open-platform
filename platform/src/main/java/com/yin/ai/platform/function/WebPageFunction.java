package com.yin.ai.platform.function;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.jsoup.safety.Safelist;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.net.InetAddress;
import java.net.URI;

/**
 * 网页读取 Function — 读取指定 URL 的网页全文内容。
 * <p>
 * 从 WebSearchTools.readWebPage() 迁移，移除了敏感词脱敏（应由 Skill 层处理）。
 */
@Slf4j
public class WebPageFunction extends AbstractFunction {

    private static final int MAX_CONTENT_LENGTH = 8000;

    @Override
    public FunctionDefinition getDefinition() {
        return FunctionDefinition.builder()
                .name("readWebPage")
                .description("读取指定URL的网页全文内容，返回标题和正文")
                .version("1.0.0")
                .category("search")
                .risk(FunctionDefinition.Risk.READ_ONLY)
                .timeoutMs(15000)
                .callableByLLM(true)
                .tags(Set.of("web", "read"))
                .parameters(List.of(
                        FunctionDefinition.ParamDef.builder()
                                .name("url")
                                .type(FunctionDefinition.ParamType.STRING)
                                .required(true)
                                .description("要读取的网页完整URL，例如 https://example.com/news/123")
                                .build()
                ))
                .returnType(FunctionDefinition.ReturnType.OBJECT)
                .build();
    }

    @Override
    protected FunctionResult doExecute(FunctionInput input) {
        String url = (String) input.getParameters().get("url");

        Map<String, String> result = new LinkedHashMap<>();
        result.put("url", url);

        try {
            String current = url;
            Connection.Response response = null;
            for (int redirects = 0; redirects <= 5; redirects++) {
                validatePublicHttpUrl(current);
                response = Jsoup.connect(current)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .timeout((int) TimeUnit.SECONDS.toMillis(15))
                        .followRedirects(false)
                        .execute();
                if (response.statusCode() < 300 || response.statusCode() >= 400) break;
                String location = response.header("Location");
                if (location == null || location.isBlank()) throw new SecurityException("redirect without location");
                current = URI.create(current).resolve(location).toString();
            }
            if (response == null || (response.statusCode() >= 300 && response.statusCode() < 400)) {
                throw new SecurityException("too many redirects");
            }
            var doc = response.parse();

            String title = doc.title();
            result.put("title", title != null ? title : "");

            String bodyText = Jsoup.clean(doc.body().text(), Safelist.none());
            if (bodyText.length() > MAX_CONTENT_LENGTH) {
                bodyText = bodyText.substring(0, MAX_CONTENT_LENGTH);
            }
            result.put("content", bodyText);

            log.info("Web page read successfully: url={}, title={}, contentLength={}",
                    url, title, bodyText.length());
        } catch (Exception e) {
            log.warn("Web page read failed: url={}", url, e);
            result.put("title", "");
            result.put("content", "无法读取该网页内容: " + e.getMessage());
        }

        return FunctionResult.ok(result);
    }

    private void validatePublicHttpUrl(String value) throws Exception {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new SecurityException("only http/https URLs are allowed");
        }
        if (uri.getUserInfo() != null || uri.getHost() == null) {
            throw new SecurityException("invalid URL host");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new SecurityException("private network address is blocked");
            }
        }
    }
}
