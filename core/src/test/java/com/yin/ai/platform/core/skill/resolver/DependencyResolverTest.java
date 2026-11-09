package com.yin.ai.platform.core.skill.resolver;

import com.yin.ai.platform.core.skill.SkillLoader;
import com.yin.ai.platform.core.skill.SkillManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * DependencyResolver 单元测试 — Mock SkillLoader，验证 DFS 拓扑排序和循环依赖检测。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DependencyResolver")
class DependencyResolverTest {

    @Mock
    private SkillLoader skillLoader;

    private DependencyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DependencyResolver(skillLoader);
    }

    private SkillManifest manifest(String name, List<String> dependencies) {
        SkillManifest m = new SkillManifest();
        m.setName(name);
        m.setDependencies(dependencies);
        m.setEnabled(true);
        return m;
    }

    @Test
    @DisplayName("空列表返回空")
    void shouldReturnEmptyForNull() {
        assertThat(resolver.resolve((List<String>) null)).isEmpty();
        assertThat(resolver.resolve(List.of())).isEmpty();
    }

    @Test
    @DisplayName("单 Skill 无依赖 → 返回自身")
    void shouldReturnSingleSkillWithoutDependencies() {
        when(skillLoader.load("summary")).thenReturn(Optional.of(manifest("summary", List.of())));

        List<String> result = resolver.resolve("summary");
        assertThat(result).containsExactly("summary");
    }

    @Test
    @DisplayName("单 Skill + 单层依赖 → 拓扑排序")
    void shouldResolveSingleLevelDependency() {
        when(skillLoader.load("chat")).thenReturn(Optional.of(manifest("chat", List.of("news-base"))));
        when(skillLoader.load("news-base")).thenReturn(Optional.of(manifest("news-base", List.of())));

        List<String> result = resolver.resolve("chat");
        assertThat(result).containsExactly("news-base", "chat");
    }

    @Test
    @DisplayName("单 Skill + 两层依赖 → 正确排序")
    void shouldResolveTwoLevelDependencies() {
        SkillManifest chat = manifest("chat", List.of("news-base"));
        SkillManifest newsBase = manifest("news-base", List.of("platform-base"));
        SkillManifest platformBase = manifest("platform-base", List.of());

        when(skillLoader.load("chat")).thenReturn(Optional.of(chat));
        when(skillLoader.load("news-base")).thenReturn(Optional.of(newsBase));
        when(skillLoader.load("platform-base")).thenReturn(Optional.of(platformBase));

        List<String> result = resolver.resolve("chat");
        assertThat(result).containsExactly("platform-base", "news-base", "chat");
    }

    @Test
    @DisplayName("多 Skill 输入 → 去重 + 拓扑排序")
    void shouldResolveMultipleSkillsWithSharedDependency() {
        SkillManifest chat = manifest("chat", List.of("news-base"));
        SkillManifest hotNews = manifest("hot-news", List.of("news-base"));
        SkillManifest newsBase = manifest("news-base", List.of());

        when(skillLoader.load("chat")).thenReturn(Optional.of(chat));
        when(skillLoader.load("hot-news")).thenReturn(Optional.of(hotNews));
        when(skillLoader.load("news-base")).thenReturn(Optional.of(newsBase));

        List<String> result = resolver.resolve(List.of("chat", "hot-news"));
        assertThat(result).hasSize(3);
        assertThat(result.indexOf("news-base")).isLessThan(result.indexOf("chat"));
        assertThat(result.indexOf("news-base")).isLessThan(result.indexOf("hot-news"));
    }

    @Test
    @DisplayName("直接循环依赖 → 抛出 CyclicDependencyException")
    void shouldDetectDirectCycle() {
        SkillManifest a = manifest("a", List.of("b"));
        SkillManifest b = manifest("b", List.of("a"));

        when(skillLoader.load("a")).thenReturn(Optional.of(a));
        when(skillLoader.load("b")).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> resolver.resolve("a"))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessageContaining("Cyclic skill dependency detected")
                .hasMessageContaining("a")
                .hasMessageContaining("b");
    }

    @Test
    @DisplayName("间接循环依赖 A→B→C→A → 抛出异常")
    void shouldDetectIndirectCycle() {
        SkillManifest a = manifest("a", List.of("b"));
        SkillManifest b = manifest("b", List.of("c"));
        SkillManifest c = manifest("c", List.of("a"));

        when(skillLoader.load("a")).thenReturn(Optional.of(a));
        when(skillLoader.load("b")).thenReturn(Optional.of(b));
        when(skillLoader.load("c")).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> resolver.resolve("a"))
                .isInstanceOf(CyclicDependencyException.class);
    }

    @Test
    @DisplayName("自依赖 → 抛出循环依赖异常")
    void shouldDetectSelfDependency() {
        SkillManifest self = manifest("self", List.of("self"));
        when(skillLoader.load("self")).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> resolver.resolve("self"))
                .isInstanceOf(CyclicDependencyException.class);
    }
}
