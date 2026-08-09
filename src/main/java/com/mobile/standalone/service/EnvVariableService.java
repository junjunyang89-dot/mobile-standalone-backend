package com.mobile.standalone.service;

import com.mobile.standalone.entity.EnvVariable;
import com.mobile.standalone.mapper.EnvVariableMapper;
import com.mobile.standalone.util.TestEnvironment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 环境变量：<b>UI 自动化和接口自动化共用一套</b>。
 * <p>
 * 解决的是同一个问题：用例两边一样，<b>数据两边不一样</b>——
 * 测试环境的登录手机号、搜索关键词、比赛 ID，跟线上都不是同一份。
 * 把数据从流程/用例里抽出来按环境注入，一条流程才能既跑线上包又跑测试包。
 * <p>
 * 刻意不做成「账号表」：不够用。账号只是其中一类，
 * 搜索词、商品 ID 一样是环境相关的，做成专表就得再开第二张、第三张。
 */
@Service
@RequiredArgsConstructor
public class EnvVariableService {

    /** 掩码用固定长度，不暴露原值长度——长度本身也是信息 */
    public static final String MASK = "••••••••••";

    private final EnvVariableMapper mapper;

    /**
     * 给界面看的。secret 一律打码。
     * <p>
     * 不提供「查看真值」的接口：真要看去数据库看。做一个能在浏览器里把密钥显示出来的按钮，
     * 等于给这份密钥多开一条泄露路径，而它换来的只是省一次登数据库。
     */
    public List<EnvVariable> listMasked(String environment) {
        List<EnvVariable> out = new ArrayList<>();
        for (EnvVariable v : mapper.findByEnvironment(TestEnvironment.normalize(environment))) {
            if (Boolean.TRUE.equals(v.getSecret()) && v.getValue() != null && !v.getValue().isEmpty()) {
                v.setValue(MASK);
            }
            out.add(v);
        }
        return out;
    }

    /** 执行时用：只取启用的，值是真值 */
    public Map<String, String> resolveValues(String environment) {
        Map<String, String> out = new LinkedHashMap<>();
        for (EnvVariable v : mapper.findByEnvironment(TestEnvironment.normalize(environment))) {
            if (Boolean.FALSE.equals(v.getEnabled())) {
                continue;
            }
            if (v.getName() != null && !v.getName().isBlank()) {
                out.put(v.getName().trim(), v.getValue() == null ? "" : v.getValue());
            }
        }
        return out;
    }

    public EnvVariable save(EnvVariable row) {
        if (row == null || row.getName() == null || row.getName().isBlank()) {
            throw new IllegalArgumentException("变量名不能为空");
        }
        // 说明里写着「用例里写 {{变量名}}」，于是不少人会把 {{phone}} 整个粘进来。
        // 意图完全清楚，直接接住，别回一句「变量名只能是字母/数字…」让人自己猜哪儿多打了
        String name = stripBraces(row.getName());
        if (!name.matches("[A-Za-z_][\\w.\\-]*")) {
            // 变量名会被写进 {{}} 里做正则匹配，允许任意字符会出现「配了但永远匹配不上」的哑巴故障
            throw new IllegalArgumentException("变量名只能是字母/数字/下划线/点/横线，且不能以数字开头：" + name);
        }
        if (name.startsWith("$")) {
            throw new IllegalArgumentException("$ 开头是内置变量（$timestamp/$randomInt/$guid），不能自定义");
        }
        row.setName(name);
        row.setEnvironment(TestEnvironment.normalize(row.getEnvironment()));
        if (row.getSecret() == null) {
            row.setSecret(false);
        }
        if (row.getEnabled() == null) {
            row.setEnabled(true);
        }
        if (row.getSortOrder() == null) {
            row.setSortOrder(100);
        }
        // 前端把掩码原样提交回来（人只改了描述没动值）时，不能把真值覆盖成一串点
        if (Boolean.TRUE.equals(row.getSecret()) && MASK.equals(row.getValue())) {
            EnvVariable old = mapper.findOne(row.getEnvironment(), name);
            row.setValue(old == null ? "" : old.getValue());
        }
        mapper.upsert(row);
        return row;
    }

    /** 去掉人顺手带上的 {{ }} / ${ } / { } */
    static String stripBraces(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim()
                .replaceAll("^\\$?\\{+\\s*", "")
                .replaceAll("\\s*}+$", "")
                .trim();
    }

    public void delete(String environment, String name) {
        mapper.delete(TestEnvironment.normalize(environment), name);
    }
}
