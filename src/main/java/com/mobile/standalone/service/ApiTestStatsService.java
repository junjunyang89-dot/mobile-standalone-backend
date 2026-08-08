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

        Double passRate = curTotal > 0 ? round1(100.0 * curPassed / curTotal) : null;
        Double prevPassRate = prevTotal > 0 ? round1(100.0 * prevPassed / prevTotal) : null;
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
            long runCount = asLong(row, "runCount");
            long passed = asLong(row, "passed");
            if (runCount > 0) {
                c.setPassRate(round1(100.0 * passed / runCount));
            } else {
                c.setPassRate(null);
            }
            collections.add(c);
        }
        out.setCollections(collections);
        return out;
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
