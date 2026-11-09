package com.yin.ai.platform.core.skill;

import com.yin.ai.platform.core.skill.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DefaultSkillLoader 单元测试 — Mock SkillRepository，验证缓存和委托逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultSkillLoader")
class DefaultSkillLoaderTest {

    @Mock
    private SkillRepository repository;

    private DefaultSkillLoader loader;

    @BeforeEach
    void setUp() {
        loader = new DefaultSkillLoader(repository);
    }

    private SkillManifest manifest(String name) {
        SkillManifest m = new SkillManifest();
        m.setName(name);
        m.setVersion("1.0.0");
        m.setEnabled(true);
        m.setSystemPromptTemplate("Prompt for " + name);
        return m;
    }

    @Test
    @DisplayName("首次加载委托到 repository")
    void shouldDelegateToRepositoryOnFirstLoad() {
        SkillManifest chat = manifest("chat");
        when(repository.load("chat")).thenReturn(Optional.of(chat));

        Optional<SkillManifest> result = loader.load("chat");
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("chat");
        verify(repository).load("chat");
    }

    @Test
    @DisplayName("第二次加载命中缓存，不调用 repository")
    void shouldCacheOnSecondLoad() {
        SkillManifest chat = manifest("chat");
        when(repository.load("chat")).thenReturn(Optional.of(chat));

        loader.load("chat");
        loader.load("chat");

        // repository.load 只被调用一次
        verify(repository).load("chat");
    }

    @Test
    @DisplayName("不存在的 Skill 返回 empty")
    void shouldReturnEmptyForUnknownSkill() {
        when(repository.load("unknown")).thenReturn(Optional.empty());

        assertThat(loader.load("unknown")).isEmpty();
    }

    @Test
    @DisplayName("disabled Skill 不缓存且返回 empty")
    void shouldSkipDisabledSkill() {
        SkillManifest disabled = manifest("deprecated");
        disabled.setEnabled(false);
        when(repository.load("deprecated")).thenReturn(Optional.of(disabled));

        assertThat(loader.load("deprecated")).isEmpty();
    }

    @Test
    @DisplayName("reload 清空缓存后重新加载")
    void shouldReloadAndClearCache() {
        SkillManifest v1 = manifest("chat");
        v1.setVersion("1.0.0");
        SkillManifest v2 = manifest("chat");
        v2.setVersion("2.0.0");

        when(repository.load("chat"))
                .thenReturn(Optional.of(v1))
                .thenReturn(Optional.of(v2));

        assertThat(loader.load("chat").get().getVersion()).isEqualTo("1.0.0");
        assertThat(loader.reload("chat").get().getVersion()).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("loadAll 返回所有 enabled Skill")
    void shouldLoadAllEnabledSkills() {
        SkillManifest chat = manifest("chat");
        SkillManifest summary = manifest("summary");
        SkillManifest disabled = manifest("disabled");
        disabled.setEnabled(false);

        when(repository.loadAll()).thenReturn(List.of(chat, summary, disabled));

        List<SkillManifest> all = loader.loadAll();
        assertThat(all).hasSize(2);
        assertThat(all.stream().map(SkillManifest::getName))
                .containsExactlyInAnyOrder("chat", "summary");
    }

    @Test
    @DisplayName("listSkillNames 返回所有缓存的 Skill 名")
    void shouldListCachedSkillNames() {
        SkillManifest chat = manifest("chat");
        when(repository.loadAll()).thenReturn(List.of(chat));

        Set<String> names = loader.listSkillNames();
        assertThat(names).contains("chat");
    }
}
