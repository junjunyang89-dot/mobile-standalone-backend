package com.mobile.standalone.service.apitest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把开发给的接口文档（Confluence 导出的 .docx）导进接口清单。
 * <p>
 * 和抓包是互补的两半，缺一半用例就不完整：
 * <ul>
 *   <li><b>抓包</b>给「能跑起来的请求」——真实 header、真实参数值、真实响应结构</li>
 *   <li><b>文档</b>给「该验什么」——必填、默认值、枚举回落、边界、哪些字段可空</li>
 * </ul>
 * 只有抓包，用例就是把这次调用重放一遍，边界一个都验不到；
 * 更糟的是它会给文档写明「可空」的字段生成非空断言，正常情况下也会红。
 * <p>
 * 文档里的东西一律标 {@code referenceOnly}（{@link ApiEndpointService#applyDocMeta} 盖章）：
 * 各家文档格式不一、也常和线上对不上，<b>不能拿它当事实生成断言</b>，
 * 只能拿来提示人「这里该测什么」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterfaceDocImportService {

    private final ApiEndpointService endpointService;

    /**
     * @param matched  文档里的接口在清单里找到了、写进去了
     * @param unmatched 文档里有、但清单里没有的接口。<b>要报出来</b>——
     *                  多半意味着这个接口还没被抓包或对比分析覆盖到，那是漏测点
     * @param branches 文档里提到的分支，用来和对比分析的结果对上
     */
    /** @param aliasApplied 按「同名透传」推断着套用过去的路径。是推断不是事实，所以单独报 */
    public record ImportResult(int matched, List<String> unmatched, List<String> aliasApplied,
                               List<String> branches, List<Map<String, Object>> details) {
    }

    public ImportResult importDoc(String project, InputStream docx, String filename) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("要先选接口归哪个项目——清单按项目分，不选就写不进去");
        }
        InterfaceDocParser.Result parsed = InterfaceDocParser.parse(docx);
        if (parsed.endpoints().isEmpty()) {
            throw new IllegalArgumentException(
                    "这份文档里没解析出接口。目前只认 Confluence 导出的 .docx，"
                            + "而且接口要写成「GET /a/b」加上入参/出参表格");
        }

        int matched = 0;
        List<String> unmatched = new ArrayList<>();
        /** 按「同名透传」套用过去的路径，要报出来让人能核 —— 这是推断，不是文档写的 */
        List<String> applied = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();

        for (var ep : parsed.endpoints()) {
            Map<String, Object> meta = buildMeta(ep, parsed.branches(), filename);
            var saved = endpointService.applyDocMeta(
                    project.trim(), ep.httpMethod(), ep.path(), meta);

            // 同一个契约常常有两条路径：GLD 直连 + DC 编排透传
            // （文档原话「data-center 编排并透传」）。文档只在其中一条下面放参数表，
            // 另一条就一个约束都没有——而人抓到的往往恰恰是没写的那条，
            // 结果就是「为什么只有一条正常用例」
            for (String alias : endpointService.sameTailPaths(
                    project.trim(), ep.httpMethod(), ep.path())) {
                endpointService.applyDocMeta(project.trim(), ep.httpMethod(), alias, meta);
                applied.add(ep.httpMethod() + " " + alias + "（与 " + ep.path() + " 同名，透传）");
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("httpMethod", ep.httpMethod());
            d.put("path", ep.path());
            d.put("params", ep.params().size());
            d.put("fields", ep.fields().size());
            d.put("nullableFields", ep.fields().stream().filter(f -> f.nullable()).count());
            d.put("ok", saved != null);
            details.add(d);
            if (saved != null) {
                matched++;
            } else {
                unmatched.add(ep.httpMethod() + " " + ep.path());
            }
        }
        log.info("[doc] {} 导入接口文档 {}：写入 {}、未匹配 {}",
                project, filename, matched, unmatched.size());
        return new ImportResult(matched, unmatched, applied, parsed.branches(), details);
    }

    private Map<String, Object> buildMeta(InterfaceDocParser.Endpoint ep,
                                          List<String> branches, String filename) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "docx");
        meta.put("file", filename);
        meta.put("httpMethod", ep.httpMethod());
        meta.put("path", ep.path());
        meta.put("branches", branches);

        List<Map<String, Object>> params = new ArrayList<>();
        for (var p : ep.params()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("required", p.required());
            m.put("default", p.defaultValue());
            // 原文说明原样留着：「其它回落 1」这种话归纳成结构化字段一定丢信息，而人看得懂
            m.put("note", p.note());
            params.add(m);
        }
        meta.put("params", params);

        List<Map<String, Object>> fields = new ArrayList<>();
        List<String> nullable = new ArrayList<>();
        for (var f : ep.fields()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            m.put("nullable", f.nullable());
            m.put("note", f.note());
            fields.add(m);
            if (f.nullable()) {
                nullable.add(f.name());
            }
        }
        meta.put("fieldSpecs", fields);
        // 单独拎一份可空字段名：生成断言时要跳过它们，
        // 埋在 fieldSpecs 里的话每个用到的地方都得自己遍历一遍，总有一处会忘
        meta.put("nullableFields", nullable);
        meta.put("fields", fields.stream().map(m -> String.valueOf(m.get("name"))).toList());
        return meta;
    }
}
