package com.mobile.standalone.service.apichange;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FieldChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.ParamChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.Report;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MustTestGuideTest {

    @Test
    void 删返回字段是P0并写出老客户端注意点() {
        EndpointChange e = ep("POST", "/login", "MODIFIED", "BREAKING");
        FieldChange f = new FieldChange();
        f.setChangeType("REMOVED");
        f.setName("token");
        e.getFields().add(f);

        MustTestGuide.Guide g = MustTestGuide.of(report(e));
        assertThat(g.p0()).isEqualTo(1);
        assertThat(g.items().get(0).priority()).isEqualTo("P0");
        assertThat(g.items().get(0).notes()).anyMatch(n -> n.contains("token") && n.contains("老包"));
    }

    @Test
    void 新增必填入参是P0() {
        EndpointChange e = ep("POST", "/bind", "MODIFIED", "BREAKING");
        ParamChange p = new ParamChange();
        p.setChangeType("ADDED");
        p.setName("smsCode");
        p.setRequired(true);
        e.getParams().add(p);

        MustTestGuide.Guide g = MustTestGuide.of(report(e));
        assertThat(g.items().get(0).priority()).isEqualTo("P0");
        assertThat(g.items().get(0).why()).contains("smsCode");
        assertThat(g.items().get(0).notes()).anyMatch(n -> n.contains("不传"));
    }

    @Test
    void 新接口是P1且提醒不要只看200() {
        EndpointChange e = ep("GET", "/feed", "ADDED", "NEW");
        MustTestGuide.Guide g = MustTestGuide.of(report(e));
        assertThat(g.p1()).isEqualTo(1);
        // 这条提醒还在，只是从「每一行重复一遍」挪到了报告级的 commonNotes：
        // 它对 99 条必测项全都一样，是规范不是本接口的注意事项，
        // 留在每行里会把真正特有的那几条挤到下面，人只看前两条就划走了
        assertThat(g.commonNotes()).anyMatch(n -> n.contains("不要只看 HTTP 200"));
        assertThat(g.items().get(0).notes())
                .as("通用规范不该再出现在单条里，否则这次改动等于没做")
                .noneMatch(n -> n.contains("不要只看 HTTP 200"));
    }

    @Test
    @DisplayName("通用做法整份报告只说一次,不进每一行")
    void 通用做法不逐行重复() {
        Report r = new Report();
        r.getEndpoints().add(ep("GET", "/a", "ADDED", "NEW"));
        r.getEndpoints().add(ep("GET", "/b", "ADDED", "NEW"));
        r.getEndpoints().add(ep("GET", "/c", "ADDED", "NEW"));
        MustTestGuide.Guide g = MustTestGuide.of(r);
        assertThat(g.commonNotes()).isNotEmpty();
        for (MustTestGuide.Item it : g.items()) {
            for (String common : g.commonNotes()) {
                assertThat(it.notes())
                        .as("「%s」在单条里又出现了一次", common)
                        .doesNotContain(common);
            }
        }
    }

    @Test
    void 只加返回字段是P2() {
        EndpointChange e = ep("GET", "/me", "MODIFIED", "COMPATIBLE");
        FieldChange f = new FieldChange();
        f.setChangeType("ADDED");
        f.setName("avatar");
        e.getFields().add(f);

        MustTestGuide.Guide g = MustTestGuide.of(report(e));
        assertThat(g.items().get(0).priority()).isEqualTo("P2");
        assertThat(g.hint()).contains("P2");
    }

    @Test
    void 空报告有提示() {
        MustTestGuide.Guide g = MustTestGuide.of(new Report());
        assertThat(g.items()).isEmpty();
        assertThat(g.hint()).contains("扫不到必测项");
    }

    private static Report report(EndpointChange e) {
        Report r = new Report();
        r.getEndpoints().add(e);
        return r;
    }

    private static EndpointChange ep(String method, String path, String change, String risk) {
        EndpointChange e = new EndpointChange();
        e.setHttpMethod(method);
        e.setPath(path);
        e.setChangeType(change);
        e.setRisk(risk);
        return e;
    }
}
