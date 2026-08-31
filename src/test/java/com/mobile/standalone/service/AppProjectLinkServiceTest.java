package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.entity.AppProjectLink;
import com.mobile.standalone.mapper.AppProjectLinkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

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
class AppProjectLinkServiceTest {

    @Mock
    private AppProjectLinkMapper mapper;
    @Mock
    private ApiProjectService projectService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AppProjectLinkService service;

    private void appExists(boolean exists) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(exists ? 1 : 0);
    }

    private void projectExists(boolean exists) {
        when(projectService.get(anyString())).thenReturn(exists ? new ApiProject() : null);
    }

    private static AppProjectLink link(String appId, String project, String platform) {
        AppProjectLink l = new AppProjectLink();
        l.setAppId(appId);
        l.setProjectCode(project);
        l.setPlatform(platform);
        return l;
    }

    @Test
    void save_normalizesPlatformAndDefaultsRelationToOwns() {
        appExists(true);
        projectExists(true);
        when(mapper.findOne(anyString(), anyString())).thenReturn(null, new AppProjectLink());

        service.save(link("DOMESTIC_ESPORTS", "app-esports-android", "android"));

        ArgumentCaptor<AppProjectLink> saved = ArgumentCaptor.forClass(AppProjectLink.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getPlatform()).isEqualTo("ANDROID");
        assertThat(saved.getValue().getRelation()).isEqualTo(AppProjectLink.RELATION_OWNS);
    }

    /** 留空表示「不分端」，要存成 null 而不是空串——SQL 里靠 IS NULL 判断是否按端过滤 */
    @Test
    void save_blankPlatformBecomesNull() {
        appExists(true);
        projectExists(true);
        when(mapper.findOne(anyString(), anyString())).thenReturn(null, new AppProjectLink());

        service.save(link("DOMESTIC_ESPORTS", "app-admin", "   "));

        ArgumentCaptor<AppProjectLink> saved = ArgumentCaptor.forClass(AppProjectLink.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getPlatform()).isNull();
    }

    /** 同一对 (appId, projectCode) 是主键，重复提交应走更新而不是插入 */
    @Test
    void save_existingPairUpdatesInsteadOfInserting() {
        appExists(true);
        projectExists(true);
        when(mapper.findOne(anyString(), anyString())).thenReturn(new AppProjectLink());

        service.save(link("DOMESTIC_ESPORTS", "app-esports-ios", "IOS"));

        verify(mapper).update(any(AppProjectLink.class));
        verify(mapper, never()).insert(any(AppProjectLink.class));
    }

    @Test
    void save_rejectsUnknownApp() {
        appExists(false);
        projectExists(true);

        assertThatThrownBy(() -> service.save(link("NOPE", "app-admin", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("App 不存在");
        verify(mapper, never()).insert(any(AppProjectLink.class));
    }

    /** 外键也会拦，但那样用户拿到的是 MySQL 报错，看不出该去哪修 */
    @Test
    void save_rejectsUnknownProject() {
        appExists(true);
        projectExists(false);

        assertThatThrownBy(() -> service.save(link("DOMESTIC_ESPORTS", "no-such-project", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目不存在");
        verify(mapper, never()).insert(any(AppProjectLink.class));
    }

    @Test
    void save_rejectsBadPlatformAndRelation() {
        appExists(true);
        projectExists(true);

        assertThatThrownBy(() -> service.save(link("DOMESTIC_ESPORTS", "app-admin", "WINDOWS")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform");

        AppProjectLink bad = link("DOMESTIC_ESPORTS", "app-admin", "IOS");
        bad.setRelation("MAYBE");
        assertThatThrownBy(() -> service.save(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relation");
    }

    @Test
    void save_rejectsBlankIds() {
        assertThatThrownBy(() -> service.save(link("", "app-admin", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appId");
        assertThatThrownBy(() -> service.save(link("DOMESTIC_ESPORTS", "", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectCode");
    }
}
