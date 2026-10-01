package com.mobile.standalone.service.apitest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一次运行的逐条结果折叠成<b>按桶分开的通过率</b>,纯计算,不碰数据库。
 *
 * <h2>为什么必须分桶</h2>
 *
 * <p>分支用例挂了意味着"这次改动有问题"(该找开发),回归用例挂了意味着
 * "回归了"(该拉警报)。混进一个分母后,"通过率 92%"不再能支撑任何决定——
 * 你不知道那 8% 该找谁。
 *
 * <p>这和 BLOCKED 不进分母是同一条原则:<b>把语义不同的结果混进一个数字,
 * 那个数字就坏了</b>,而且坏得看不出来——它仍然是个合理的百分数。
 *
 * <h2>为什么折叠在 Java 里而不是 SQL 里</h2>
 *
 * <p>SQL 只做 {@code GROUP BY case_kind},出来三四行;分桶规则留在
 * {@link ApiCaseKind#passRateBucket()} 一个地方。写成 SQL 里的
 * {@code CASE WHEN case_kind = 'BRANCH' THEN ...} 的话,
 * 同一条规则就有了两份实现,将来改一边忘另一边,报表会和界面对不上。
 *
 * <h2>"没记录"必须单独成桶</h2>
 *
 * <p>{@code case_kind} 为空的行有两种来源:加这个字段之前的历史运行,
 * 以及"用例查不到"的那些 BLOCKED。它们的真相是<b>不知道</b>,
 * 不是"未分类"。
 *
 * <p>走 {@link ApiCaseKind#of} 会把空值变成 UNCLASSIFIED,
 * 而 UNCLASSIFIED 的桶是 REGRESSION —— 于是"不知道"被悄悄算进了
 * 回归通过率的分母。所以这里用 {@link ApiCaseKind#strict},
 * 认不出来的一律进 {@link #UNRECORDED}。
 */
public final class RunPassRateBuckets {

    private RunPassRateBuckets() {
    }

    /** 种类没记录,或者记了个认不出来的值 */
    public static final String UNRECORDED = "UNRECORDED";

    /**
     * 一个桶的统计。
     *
     * @param bucket  桶名:{@code BRANCH} / {@code REGRESSION} / {@link #UNRECORDED}
     * @param total   这个桶里一共几条
     * @param passed  通过几条
     * @param blocked 被拦掉几条(限流、连不上、用例不存在……一条断言都没跑到)
     */
    public record Bucket(String bucket, long total, long passed, long blocked) {

        /** 真正跑起来的条数——分母 */
        public long ran() {
            return Math.max(0, total - blocked);
        }

        /**
         * 通过率。<b>一条都没跑成时返回 {@code null},不是 0</b>。
         *
         * <p>0% 的意思是"跑了,全挂了";没跑成是另一回事。
         * 返回 0 的话,界面上一片红,人会去查用例和断言,
         * 而真实原因是限流或者环境连不上。
         */
        public Double passRate() {
            long ran = ran();
            if (ran <= 0) {
                return null;
            }
            return Math.round(1000.0 * passed / ran) / 10.0;
        }

        /** 这个桶有没有真正跑起来的用例 */
        public boolean hasRun() {
            return ran() > 0;
        }
    }

    /**
     * 折叠。
     *
     * @param rows {@code GROUP BY case_kind} 的结果,每行
     *             {@code {caseKind, total, passed, blocked}};
     *             键名大小写不敏感,缺的键按 0 算
     * @return 按 {@code BRANCH} → {@code REGRESSION} → {@code UNRECORDED} 固定顺序,
     *         <b>只包含真的有数据的桶</b>——空桶不返回,
     *         否则界面上会出现一个"通过率 —"的分支用例栏,
     *         而这次运行里根本没有分支用例
     */
    public static List<Bucket> fold(List<Map<String, Object>> rows) {
        Map<String, long[]> acc = new LinkedHashMap<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                if (r == null) {
                    continue;
                }
                String raw = str(r, "caseKind", "case_kind");
                ApiCaseKind k = ApiCaseKind.strict(raw);
                // 认不出来（含空值）一律「没记录」，不能走 of() 落到 UNCLASSIFIED
                String bucket = k == null ? UNRECORDED : k.passRateBucket();
                long[] a = acc.computeIfAbsent(bucket, x -> new long[3]);
                a[0] += num(r, "total");
                a[1] += num(r, "passed");
                a[2] += num(r, "blocked");
            }
        }
        List<Bucket> out = new ArrayList<>();
        // 固定顺序：报表两次跑出来栏目顺序不一样的话，人没法对比
        for (String b : new String[]{"BRANCH", "REGRESSION", UNRECORDED}) {
            long[] a = acc.get(b);
            if (a != null && a[0] > 0) {
                out.add(new Bucket(b, a[0], a[1], a[2]));
            }
        }
        return out;
    }

    private static String str(Map<String, Object> r, String... keys) {
        for (String k : keys) {
            Object v = r.get(k);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private static long num(Map<String, Object> r, String key) {
        Object v = r.get(key);
        if (v == null) {
            // 驼峰取不到就试下划线：不同 mapper 的 resultType 键名风格不一致
            v = r.get(toSnake(key));
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String toSnake(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isUpperCase(c)) {
                b.append('_').append(Character.toLowerCase(c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
