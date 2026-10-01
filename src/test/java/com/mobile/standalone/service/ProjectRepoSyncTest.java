package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectRepoSyncTest {

    @Test
    void httpAndSshUrlsOfSameRepoNormalizeEqual() {
        String http = ProjectRepoSync.normalizeUrl("http://gitlab.shdev.net/SteamCN/data-center.git");
        assertThat(ProjectRepoSync.normalizeUrl("https://user:token@gitlab.shdev.net/SteamCN/data-center/")).isEqualTo(http);
        assertThat(ProjectRepoSync.normalizeUrl("git@gitlab.shdev.net:SteamCN/data-center.git")).isEqualTo(http);
        assertThat(ProjectRepoSync.normalizeUrl("http://gitlab.shdev.net/SteamCN/data-center-v2.git")).isNotEqualTo(http);
    }

    @Test
    void repoNameIsLastSegment() {
        assertThat(ProjectRepoSync.repoNameOf("http://gitlab.shdev.net/Esports/app_esports_android.git"))
                .isEqualTo("app_esports_android");
        assertThatThrownBy(() -> ProjectRepoSync.repoNameOf("http://gitlab.shdev.net/a/.."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOnlyCloneUrls() {
        assertThat(ProjectRepoSync.looksLikeGitUrl("http://gitlab.shdev.net/SteamCN/robot.git")).isTrue();
        assertThat(ProjectRepoSync.looksLikeGitUrl("git@gitlab.shdev.net:SteamCN/robot.git")).isTrue();
        assertThat(ProjectRepoSync.looksLikeGitUrl("gitlab.shdev.net/SteamCN/robot")).isFalse();
        assertThat(ProjectRepoSync.looksLikeGitUrl("/Users/x/robot")).isFalse();
    }
}
