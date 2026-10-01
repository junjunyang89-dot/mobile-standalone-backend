package com.mobile.standalone.service.convention;

import com.mobile.standalone.service.convention.ConventionRules.Baseline;
import com.mobile.standalone.service.convention.ConventionRules.Observation;
import com.mobile.standalone.service.convention.ConventionRules.Result;
import com.mobile.standalone.service.convention.ConventionRules.Rule;
import com.mobile.standalone.service.convention.ConventionRules.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConventionRulesTest {

    private static final Path REAL_ANDROID = Path.of(System.getProperty("user.home"),
            "projects/qa_coding/app_coding/app_esports_android");

    @TempDir
    Path repo;

    private static Rule rule(String id) {
        return ConventionRules.DEFAULTS.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private void write(String rel, String text) throws IOException {
        Path f = repo.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private static Baseline counted(int total, Map<String, Integer> files) {
        return new Baseline(total, files, List.of(), "t", "c", null);
    }

    @Test
    void X5入口涨了报REGRESSED并指出是哪个文件() throws IOException {
        write("a/Old.kt", "PWx5WebActivity.start(ctx, url)\nPWx5WebActivity.startFullScreen(ctx, url)\n");
        write("b/New.kt", "val x = 1\n\nPWx5WebActivity.start(ctx, pay)\n");
        Rule r = rule("x5-entry");

        Result res = ConventionRules.evaluate(r, ConventionRules.observe(r, repo),
                counted(2, Map.of("a/Old.kt", 2)));

        assertThat(res.status()).isEqualTo(Status.REGRESSED);
        assertThat(res.current()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grown = (List<Map<String, Object>>) res.evidence().get("grown");
        assertThat(grown).singleElement().satisfies(g -> {
            assertThat(g.get("file")).isEqualTo("b/New.kt");
            assertThat(g.get("lines")).isEqualTo(List.of(3));
        });
    }

    @Test
    void 计数少了是IMPROVED_持平是OK_没baseline不算过() throws IOException {
        write("A.java", "PWx5WebActivity.start(a);");
        Rule r = rule("x5-entry");
        Observation obs = ConventionRules.observe(r, repo);

        assertThat(ConventionRules.evaluate(r, obs, counted(3, Map.of())).status()).isEqualTo(Status.IMPROVED);
        assertThat(ConventionRules.evaluate(r, obs, counted(1, Map.of())).status()).isEqualTo(Status.OK);
        assertThat(ConventionRules.evaluate(r, obs, null).status()).isEqualTo(Status.NO_BASELINE);
    }

    @Test
    void build目录和非源码后缀不算() throws IOException {
        write("app/build/gen/X.kt", "PWx5WebActivity.start(a)");
        write("docs/x.md", "PWx5WebActivity.start(a)");
        Rule r = rule("x5-entry");

        assertThat(ConventionRules.observe(r, repo).hits()).isEmpty();
    }

    @Test
    void 空onReceivedError_单行和多行都能认出来_有内容的不算() throws IOException {
        write("A.kt", "override fun onReceivedError(webView: WebView?, errorCode: Int, description: String?, url: String?) {}\n");
        write("B.java", "public void onReceivedError(@Nullable WebView webView, int errorCode, @Nullable String d, @Nullable String url) {\n\n    }\n");
        write("C.kt", "override fun onReceivedError(v: WebView?, c: Int, d: String?, u: String?) {\n    log(d)\n}\n");
        Rule r = rule("empty-on-received-error");

        assertThat(ConventionRules.observe(r, repo).hits()).containsOnlyKeys("A.kt", "B.java");
    }

    @Test
    void 两份JSBridge有一份没跟上时指出多在哪一份() throws IOException {
        Rule r = rule("jsbridge-parity");
        String a = r.paths().get(0);
        String b = r.paths().get(1);
        write(a, "when (name) {\n  \"getAccountInfo\" -> x()\n  \"setHomePageBg\" -> y()\n  else -> {}\n}");
        write(b, "when (name) {\n  \"getAccountInfo\" -> x()\n  else -> {}\n}");

        Result res = ConventionRules.evaluate(r, ConventionRules.observe(r, repo), null);

        assertThat(res.status()).isEqualTo(Status.REGRESSED);
        assertThat(res.evidence().get("onlyIn")).isEqualTo(Map.of(a, Set.of("setHomePageBg"), b, Set.of()));
    }

    @Test
    void 两份一致是OK_文件不在是SKIPPED而不是OK() throws IOException {
        Rule r = rule("jsbridge-parity");
        write(r.paths().get(0), "  \"getAccountInfo\" -> x()\n");

        assertThat(ConventionRules.evaluate(r, ConventionRules.observe(r, repo), null).status())
                .isEqualTo(Status.SKIPPED);

        write(r.paths().get(1), "  \"getAccountInfo\" -> x()\n");
        assertThat(ConventionRules.evaluate(r, ConventionRules.observe(r, repo), null).status())
                .isEqualTo(Status.OK);
    }

    @Test
    void 新增的服务端开关没登记报REGRESSED() throws IOException {
        Rule r = rule("server-switch-registry");
        write(r.paths().get(0), "PWSharedPrefConst.MY_TAB_SYS_WEB_SWITCH\nPWSharedPrefConst.NEW_PAY_SWITCH\n");
        Baseline registered = new Baseline(1, Map.of(), List.of("MY_TAB_SYS_WEB_SWITCH"), "t", "c", null);

        Result res = ConventionRules.evaluate(r, ConventionRules.observe(r, repo), registered);

        assertThat(res.status()).isEqualTo(Status.REGRESSED);
        assertThat(res.evidence().get("unregistered")).isEqualTo(Set.of("NEW_PAY_SWITCH"));
    }

    @Test
    void baseline只许往下锁_往上锁要force加原因() throws IOException {
        write("A.kt", "PWx5WebActivity.start(a)\nPWx5WebActivity.start(b)\n");
        Rule r = rule("x5-entry");
        Observation obs = ConventionRules.observe(r, repo);
        Baseline one = counted(1, Map.of());

        assertThatThrownBy(() -> ConventionRules.lock(r, obs, one, false, null, "c"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("只许往下锁");
        assertThatThrownBy(() -> ConventionRules.lock(r, obs, one, true, " ", "c"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("reason");
        assertThat(ConventionRules.lock(r, obs, one, true, "临时接入第三方页", "c").total()).isEqualTo(2);
        assertThat(ConventionRules.lock(r, obs, counted(5, Map.of()), false, null, "c").files())
                .isEqualTo(Map.of("A.kt", 2));
    }

    static boolean realAndroidRepo() {
        return Files.isDirectory(REAL_ANDROID);
    }

    @Test
    @EnabledIf("realAndroidRepo")
    void 对本机真实Android仓库每条规则都跑得出结果() throws IOException {
        for (Rule r : ConventionRules.DEFAULTS) {
            Result res = ConventionRules.evaluate(r, ConventionRules.observe(r, REAL_ANDROID), null);
            assertThat(res.status()).as(r.id() + ": " + res.detail()).isNotEqualTo(Status.SKIPPED);
            assertThat(res.current()).as(r.id()).isPositive();
        }
    }
}
