package com.mobile.standalone.service.capture;

import com.mobile.standalone.auth.PermissionRules;
import com.mobile.standalone.auth.PlatformRole;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「代理上见到了哪些域名」这份诊断的读取。
 * <p>
 * 这不是锦上添花的统计：「一条都没抓到」有三种原因——代理没通、证书没装、
 * 域名不在白名单——排查方向完全不同，而这份清单是区分它们的唯一依据。
 * 格式坏掉的话，人只会看到一个「0 条」，然后回去用手机上那个难用的抓包工具。
 */
class CaptureSeenHostsTest {

    private final PacketCaptureService service = new PacketCaptureService(org.mockito.Mockito.mock(CaptureStore.class));

    @Test
    void 读得出插件写的域名计数() throws Exception {
        Path out = Files.createTempFile("cap-", ".jsonl");
        Files.writeString(Path.of(out.toAbsolutePath() + ".hosts"),
                "{\"gateway.arca42.com\":12,\"log.example.cn\":40}", StandardCharsets.UTF_8);

        Map<String, Integer> seen = service.readSeen(out);

        assertThat(seen).containsEntry("gateway.arca42.com", 12)
                .containsEntry("log.example.cn", 40);
    }

    @Test
    void 文件还没生成时返回空而不是报错() throws Exception {
        // 抓包刚起来、手机还没发请求时就是这个状态，不能让页面在这里崩掉
        Path out = Files.createTempFile("cap-", ".jsonl");
        assertThat(service.readSeen(out)).isEmpty();
    }

    @Test
    void 文件写坏了也不该把页面拖垮() throws Exception {
        Path out = Files.createTempFile("cap-", ".jsonl");
        // 插件是边抓边覆盖写的，读到一半截断的内容完全可能
        Files.writeString(Path.of(out.toAbsolutePath() + ".hosts"),
                "{\"gateway.arca42.com\":1", StandardCharsets.UTF_8);

        assertThat(service.readSeen(out)).isEmpty();
    }

    @Test
    void 抓包会改手机代理设置所以只读账号进不去() {
        assertThat(PermissionRules.minRoleForRoute("/packet-capture"))
                .isEqualTo(PlatformRole.TESTER);
    }

    @Test
    void H5域名会带端口一起忽略() {
        List<String> patterns = PacketCaptureService.ignoreHostPatterns("pogpog.gg,unpkg.com");
        assertThat(patterns).containsExactly(
                PacketCaptureService.ignoreHostRegex("pogpog.gg"),
                PacketCaptureService.ignoreHostRegex("unpkg.com"));

        Pattern pog = Pattern.compile(PacketCaptureService.ignoreHostRegex("pogpog.gg"));
        assertThat(pog.matcher("www-qa.pogpog.gg:443").find()).isTrue();
        assertThat(pog.matcher("pogpog.gg:443").find()).isTrue();
        assertThat(pog.matcher("gateway-qa.arca42.com:443").find()).isFalse();
    }

    @Test
    void 接口域名带端口仍能被当作要抓的对象() {
        // ignore 不能误伤网关，否则就是「页面能开、抓包是空的」
        Pattern pog = Pattern.compile(PacketCaptureService.ignoreHostRegex("pogpog.gg"));
        assertThat(pog.matcher("gwapi-staging.pwesports.cn:443").find()).isFalse();
        assertThat(pog.matcher("172.16.108.140:443").find()).isFalse();
    }

    @Test
    void 子域和hostPort都能命中且不会误伤后缀相似的域名() {
        String re = PacketCaptureService.ignoreHostRegex("arca42.com");
        Pattern p = Pattern.compile(re);
        assertThat(p.matcher("arca42.com").find()).isTrue();
        assertThat(p.matcher("arca42.com:443").find()).isTrue();
        assertThat(p.matcher("gateway-qa.arca42.com:443").find()).isTrue();
        assertThat(p.matcher("evilarca42.com:443").find()).isFalse();
    }
}
