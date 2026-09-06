package com.mobile.standalone.service.apichange;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FieldChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.ParamChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.Report;
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
        assertThat(g.items().get(0).notes()).anyMatch(n -> n.contains("不要只看 HTTP 200"));
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
