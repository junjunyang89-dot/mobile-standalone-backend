package com.mobile.standalone.service.apitest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口覆盖差集。
 * <p>
 * 把两条独立链路的结论摆在一起看：
 * <ul>
 *   <li><b>接口对比分析</b>（{@code source=SYNC}）——从代码算出「这个需求改了哪些接口」</li>
 *   <li><b>前端抓包</b>（{@code captured_at} 非空）——从报文看出「这个需求实际调了哪些接口」</li>
 * </ul>
 * 两边都有的是正常覆盖；只有一边的才是要看的东西：
 * <ul>
 *   <li><b>改了但没抓到</b>：后端动了、前端一次都没调。要么这条路没测到，
 *       要么这次改动前端根本用不上——两种都值得当场问清楚，而不是等上线</li>
 *   <li><b>抓到但不在变更里</b>：前端调了本次改动范围之外的接口。它们没被
 *       变更分析覆盖，却实实在在跑在这条业务链路上，是回归风险面</li>
 * </ul>
 * <p>
 * 这两个数字原先算不出来，不是缺数据而是缺一个字段：{@code linkCaptured} 刻意不改
 * {@code source}（抓包不否定它被改过），于是 SYNC 接口被抓到之后，
 * 「改了且覆盖了」和「改了没覆盖」在库里长得一模一样。补了 {@code captured_at} 才分得开。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoverageGapService {

    /** 差集分类 */
    public static final String GAP_NOT_CAPTURED = "CHANGED_NOT_CAPTURED";
    public static final String GAP_NOT_ANALYZED = "CAPTURED_NOT_CHANGED";
    public static final String COVERED = "COVERED";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 单个需求的覆盖差集明细。
     *
     * @param includeArchived 是否含已上线归档的。默认不含——上线之后再看差集没有意义
     */
    public Map<String, Object> forRequirement(String requirementNo, boolean includeArchived) {
        if (requirementNo == null || requirementNo.isBlank()) {
            throw new IllegalArgumentException("需求单号必填");
        }
        String no = requirementNo.trim();
        String lifecycleClause = includeArchived
                ? ""
                : " AND (lifecycle IS NULL OR lifecycle <> 'ARCHIVED')";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT http_method AS httpMethod, path, summary, source, change_flag AS changeFlag,
                       change_note AS changeNote, captured_at AS capturedAt, verified,
                       last_run_status AS lastRunStatus, project
                  FROM requirement_endpoints
                 WHERE requirement_no = ?
                """ + lifecycleClause + " ORDER BY path", no);

        List<Map<String, Object>> changedNotCaptured = new ArrayList<>();
        List<Map<String, Object>> capturedNotChanged = new ArrayList<>();
        List<Map<String, Object>> covered = new ArrayList<>();

        for (Map<String, Object> r : rows) {
            String source = str(r.get("source"));
            boolean captured = r.get("capturedAt") != null;
            // MANUAL 是人工补的，既不代表「代码改了」也不代表「前端调了」，
            // 归进差集会把人工录入当成分析结论。单独计数，不进任何一边
            if ("MANUAL".equals(source)) {
                continue;
            }
            if ("SYNC".equals(source)) {
                (captured ? covered : changedNotCaptured).add(withGap(r, captured ? COVERED : GAP_NOT_CAPTURED));
            } else if ("CAPTURE".equals(source)) {
                capturedNotChanged.add(withGap(r, GAP_NOT_ANALYZED));
            }
        }

        long manual = rows.stream().filter(r -> "MANUAL".equals(str(r.get("source")))).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requirementNo", no);
        out.put("total", rows.size());
        out.put("coveredCount", covered.size());
        out.put("changedNotCapturedCount", changedNotCaptured.size());
        out.put("capturedNotChangedCount", capturedNotChanged.size());
        out.put("manualCount", manual);
        out.put("covered", covered);
        out.put("changedNotCaptured", changedNotCaptured);
        out.put("capturedNotChanged", capturedNotChanged);
        out.put("hint", buildHint(changedNotCaptured.size(), capturedNotChanged.size(), covered.size()));
        return out;
    }

    /** 全部需求的差集概览，用于看板 */
    public List<Map<String, Object>> summary(boolean includeArchived) {
        String lifecycleClause = includeArchived
                ? ""
                : " WHERE lifecycle IS NULL OR lifecycle <> 'ARCHIVED'";
        return jdbcTemplate.queryForList("""
                SELECT requirement_no AS requirementNo,
                       COUNT(*) AS total,
                       SUM(source = 'SYNC' AND captured_at IS NULL)     AS changedNotCaptured,
                       SUM(source = 'SYNC' AND captured_at IS NOT NULL) AS covered,
                       SUM(source = 'CAPTURE')                          AS capturedNotChanged,
                       SUM(source = 'MANUAL')                           AS manual
                  FROM requirement_endpoints
                """ + lifecycleClause + """
                 GROUP BY requirement_no
                 ORDER BY changedNotCaptured DESC, requirementNo
                """);
    }

    /**
     * 把两个数字翻成一句人话。
     * <p>
     * 差集报告最容易变成「两列数字，看完不知道该干嘛」。这里直接说下一步做什么。
     */
    static String buildHint(int changedNotCaptured, int capturedNotChanged, int covered) {
        if (changedNotCaptured == 0 && capturedNotChanged == 0) {
            return covered == 0
                    ? "还没有数据：跑一次接口对比分析，再从前端入口抓一次包"
                    : "变更接口都被抓到了，覆盖完整";
        }
        StringBuilder sb = new StringBuilder();
        if (changedNotCaptured > 0) {
            sb.append(changedNotCaptured).append(" 个接口改了但抓包没捕获到——")
              .append("确认是这条路没走到（漏测），还是前端本来就不调它（后端白改）。");
        }
        if (capturedNotChanged > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(capturedNotChanged).append(" 个接口被调用但不在本次变更里——")
              .append("它们没被变更分析覆盖，属于回归风险面，值得挑重点补用例。");
        }
        return sb.toString();
    }

    private static Map<String, Object> withGap(Map<String, Object> row, String gap) {
        Map<String, Object> m = new LinkedHashMap<>(row);
        m.put("gap", gap);
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
