package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.dto.ReleaseDocDtos.IssueRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseDocQaSortTest {

    @Test
    void sortByConfiguredQaOrder() {
        ReleaseDocProperties props = new ReleaseDocProperties();
        props.setQaSortOrder(List.of(
                "zhangquanchao", "张全超",
                "qinxigui", "钦熙贵",
                "ouwenyu", "欧文玉",
                "yangjunjun", "杨俊俊"
        ));
        ReleaseDocService svc = new ReleaseDocService(props);

        List<IssueRow> rows = new ArrayList<>();
        rows.add(row("A1", "yangjunjun"));
        rows.add(row("A2", "欧文玉"));
        rows.add(row("A3", "zhangquanchao"));
        rows.add(row("A4", "张全超"));
        rows.add(row("A5", "ouwenyu"));
        rows.add(row("A6", "someone-else"));

        List<IssueRow> sorted = svc.sortRowsByQa(rows);
        assertEquals(List.of("A3", "A4", "A5", "A2", "A1", "A6"),
                sorted.stream().map(IssueRow::getIssueKey).toList());
    }

    private static IssueRow row(String key, String qa) {
        IssueRow r = new IssueRow();
        r.setIssueKey(key);
        r.setQaAssignee(qa);
        r.setSummary(key);
        return r;
    }
}
