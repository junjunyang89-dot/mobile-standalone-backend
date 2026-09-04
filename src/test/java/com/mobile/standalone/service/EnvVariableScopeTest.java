package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.entity.EnvVariable;
import com.mobile.standalone.mapper.EnvVariableMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvVariableScopeTest {

    @Mock
    private EnvVariableMapper mapper;
    @Mock
    private ApiProjectService projectService;

    @InjectMocks
    private EnvVariableService service;

    private static EnvVariable var(String project, String name, String value) {
        EnvVariable v = new EnvVariable();
        v.setEnvironment("TEST");
        v.setProject(project);
        v.setName(name);
        v.setValue(value);
        v.setEnabled(true);
        return v;
    }

    /**
     * 覆盖靠的是查询顺序（全局在前、项目专属在后）而不是比较优先级。
     * 顺序错了这条就会红——这正是要盯住的地方。
     */
    @Test
    void resolveValues_projectScopedOverridesGlobal() {
        when(mapper.findByEnvironmentAndProject("TEST", "app-data-center")).thenReturn(List.of(
                var("", "token", "全局token"),
                var("", "phone", "13800000000"),
                var("app-data-center", "token", "项目token")));

        Map<String, String> out = service.resolveValues("TEST", "app-data-center");

        assertThat(out.get("token")).isEqualTo("项目token");
        assertThat(out.get("phone")).isEqualTo("13800000000");
    }

    /** 不传项目时退化成只取全局，老调用方行为不变 */
    @Test
    void resolveValues_withoutProjectFallsBackToGlobalScope() {
        when(mapper.findByEnvironmentAndProject("TEST", "")).thenReturn(List.of(
                var("", "phone", "13800000000")));

        assertThat(service.resolveValues("TEST")).containsExactly(
                org.assertj.core.data.MapEntry.entry("phone", "13800000000"));
    }

    @Test
    void resolveValues_skipsDisabledVariables() {
        EnvVariable off = var("", "phone", "13800000000");
        off.setEnabled(false);
        when(mapper.findByEnvironmentAndProject(anyString(), anyString())).thenReturn(List.of(off));

        assertThat(service.resolveValues("TEST", null)).isEmpty();
    }

    /** null / 空白项目一律归一成空串——它是主键的一部分，不能是 null */
    @Test
    void save_normalizesBlankProjectToGlobalSentinel() {
        service.save(var("   ", "phone", "1380"));

        ArgumentCaptor<EnvVariable> saved = ArgumentCaptor.forClass(EnvVariable.class);
        verify(mapper).upsert(saved.capture());
        assertThat(saved.getValue().getProject()).isEmpty();
    }

    /**
     * project 进了主键却挂不了外键（'' 在 api_projects 里不存在），
     * 存在性只能在 Service 把住，否则写错项目码就是个永远取不到值的哑变量。
     */
    @Test
    void save_rejectsUnknownProject() {
        when(projectService.get("no-such")).thenReturn(null);

        assertThatThrownBy(() -> service.save(var("no-such", "phone", "1380")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目不存在");
        verify(mapper, never()).upsert(any());
    }

    @Test
    void save_acceptsExistingProject() {
        when(projectService.get("app-data-center")).thenReturn(new ApiProject());

        service.save(var("app-data-center", "token", "abc"));

        ArgumentCaptor<EnvVariable> saved = ArgumentCaptor.forClass(EnvVariable.class);
        verify(mapper).upsert(saved.capture());
        assertThat(saved.getValue().getProject()).isEqualTo("app-data-center");
    }

    /** 掩码回传时要按 (env, project, name) 三元组回查真值，少一维会取错行 */
    @Test
    void save_keepsSecretValueWhenMaskSubmittedBack() {
        EnvVariable existing = var("app-data-center", "token", "真值");
        when(projectService.get("app-data-center")).thenReturn(new ApiProject());
        when(mapper.findOne(eq("TEST"), eq("app-data-center"), eq("token"))).thenReturn(existing);

        EnvVariable incoming = var("app-data-center", "token", EnvVariableService.MASK);
        incoming.setSecret(true);
        service.save(incoming);

        ArgumentCaptor<EnvVariable> saved = ArgumentCaptor.forClass(EnvVariable.class);
        verify(mapper).upsert(saved.capture());
        assertThat(saved.getValue().getValue()).isEqualTo("真值");
    }
}
