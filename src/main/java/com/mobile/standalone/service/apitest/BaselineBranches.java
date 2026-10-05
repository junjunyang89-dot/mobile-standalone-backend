package com.mobile.standalone.service.apitest;

import java.util.Locale;
import java.util.Set;

/**
 * 基线分支：master 是全量，testing 是测试环境的集成分支，它们都不是「某个需求的分支」。
 * <p>
 * 拿 master 对比 testing 得到的是测试环境里所有在测需求的并集，挂到分支用例下
 * 会变成一个谁都认领不了的节点；上线时也没有「testing 上线」这回事。
 */
public final class BaselineBranches {

    private BaselineBranches() {
    }

    private static final Set<String> NAMES = Set.of("master", "main", "testing", "test", "develop", "dev");

    /**
     * @param projectBase 项目管理里配的基线分支，可空
     */
    public static boolean isBaseline(String branch, String projectBase) {
        String b = normalize(branch);
        if (b.isEmpty()) {
            return false;
        }
        if (NAMES.contains(b)) {
            return true;
        }
        String base = normalize(projectBase);
        return !base.isEmpty() && base.equals(b);
    }

    /** 固定的那几个名字，不含各项目自己配的基线 */
    public static Set<String> names() {
        return NAMES;
    }

    static String normalize(String branch) {
        if (branch == null) {
            return "";
        }
        String b = branch.trim();
        if (b.startsWith("origin/")) {
            b = b.substring("origin/".length());
        }
        return b.toLowerCase(Locale.ROOT);
    }
}
