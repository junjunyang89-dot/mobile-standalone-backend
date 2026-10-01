package com.mobile.standalone.service;

import com.mobile.standalone.dto.ApiTestDtos.CollectionStat;
import com.mobile.standalone.dto.ApiTestDtos.RecentRunItem;
import com.mobile.standalone.dto.ApiTestDtos.StatsResponse;
import com.mobile.standalone.entity.ApiTestRunCase;
import com.mobile.standalone.mapper.ApiTestCaseMapper;
import com.mobile.standalone.mapper.ApiTestRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 接口测试统计面板数据。
 */
@Service
@RequiredArgsConstructor
public class ApiTestStatsService {

    private static final int RECENT_LIMIT = 20;

    private final ApiTestCaseMapper caseMapper;
    private final ApiTestRunMapper runMapper;

    public StatsResponse getStats() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since7 = now.minus(7, ChronoUnit.DAYS);
        LocalDateTime since14 = now.minus(14, ChronoUnit.DAYS);

        StatsResponse out = new StatsResponse();
        out.setUpdatedAt(now);

        long cases = caseMapper.countAll();
        out.setCases(cases);
        out.setCasesDelta7d(runMapper.countCasesCreatedSince(since7));

        Map<String, Object> cur = runMapper.aggregateCaseResults(since7, null);
        Map<String, Object> prev = runMapper.aggregateCaseResults(since14, since7);

        long curTotal = asLong(cur, "total");
        long curPassed = asLong(cur, "passed");
        long curFailed = asLong(cur, "failed");
        Double curAvg = asDouble(cur, "avgLatencyMs");

        long prevTotal = asLong(prev, "total");
        long prevPassed = asLong(prev, "passed");
        long prevFailed = asLong(prev, "failed");
        Double prevAvg = asDouble(prev, "avgLatencyMs");

        Double passRate = passRate(curPassed, curTotal, asLong(cur, "blocked"));
        Double prevPassRate = passRate(prevPassed, prevTotal, asLong(prev, "blocked"));
        out.setPassRate(passRate);
        if (passRate != null && prevPassRate != null) {
            out.setPassRateDelta(round1(passRate - prevPassRate));
        } else {
            out.setPassRateDelta(null);
        }

        out.setFailed(curFailed);
        out.setFailedDelta(curFailed - prevFailed);

        if (curAvg != null) {
            out.setAvgLatencyMs(Math.round(curAvg));
        }
        if (curAvg != null && prevAvg != null) {
            out.setAvgLatencyDeltaMs(Math.round(curAvg - prevAvg));
        }

        List<ApiTestRunCase> recent = runMapper.findRecentCases(RECENT_LIMIT);
        List<RecentRunItem> items = new ArrayList<>();
        for (ApiTestRunCase row : recent) {
            RecentRunItem item = new RecentRunItem();
            item.setId(row.getId());
            item.setCaseId(row.getCaseId());
            item.setName(row.getName());
            item.setMethod(row.getMethod());
            item.setPath(row.getPath() != null ? row.getPath() : row.getUrl());
            item.setStatus(row.getStatus());
            item.setDurationMs(row.getDurationMs());
            item.setCreatedAt(row.getCreatedAt());
            items.add(item);
        }
        out.setRecentRuns(items);

        List<Map<String, Object>> byProject = runMapper.aggregateByProject(since7);
        List<CollectionStat> collections = new ArrayList<>();
        for (Map<String, Object> row : byProject) {
            CollectionStat c = new CollectionStat();
            String project = String.valueOf(row.getOrDefault("project", "(未分组)"));
            c.setProject(project);
            c.setName(project);
            c.setCaseCount(asLong(row, "caseCount"));
            c.setPassRate(passRate(asLong(row, "passed"), asLong(row, "runCount"), asLong(row, "blocked")));
            collections.add(c);
        }
        out.setCollections(collections);
        return out;
    }

    /**
     * 通过率。没跑成（BLOCKED）的不进分母：服务挂了那批压根没走到断言，
     * 算进去的话「通过率 40%」会让人去查用例，而真实原因是服务没起来。
     * 全部没跑成时返回 null（显示「—」），而不是 0%。
     */
    static Double passRate(long passed, long total, long blocked) {
        long ran = total - blocked;
        return ran > 0 ? round1(100.0 * passed / ran) : null;
    }

    private static long asLong(Map<String, Object> map, String key) {
        if (map == null) {
            return 0L;
        }
        Object v = map.get(key);
        if (v == null) {
            // MyBatis/MySQL 可能返回大写或不同 key
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                    v = e.getValue();
                    break;
                }
            }
        }
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Double asDouble(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v == null) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                    v = e.getValue();
                    break;
                }
            }
        }
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
