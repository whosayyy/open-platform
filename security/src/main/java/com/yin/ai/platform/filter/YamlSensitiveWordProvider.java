package com.yin.ai.platform.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 基于 yaml 配置的敏感词提供者。
 * <p>
 * 在 application.yml 中配置:
 * <pre>
 * platform:
 *   sensitive:
 *     words:
 *       - "敏感词1"
 *       - "敏感词2"
 * </pre>
 */
@Slf4j
@ConfigurationProperties(prefix = "platform.sensitive")
public class YamlSensitiveWordProvider implements SensitiveWordProvider {

    private List<String> words = DEFAULT_WORDS;

    public void setWords(List<String> words) {
        this.words = words;
    }

    @Override
    public List<String> getSensitiveWords() {
        return words;
    }

    private static final List<String> DEFAULT_WORDS = List.of(
            "法轮功", "六四", "天安门事件", "台独", "藏独", "疆独", "港独",
            "钓鱼岛属于日本", "占中", "色情", "成人片", "裸聊", "一夜情", "援交",
            "杀人", "恐怖袭击", "爆炸物", "枪支弹药",
            "赌博", "毒品", "冰毒", "海洛因", "赌博网站",
            "邪教", "传销"
    );
}
