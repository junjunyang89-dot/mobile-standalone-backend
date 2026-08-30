package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.ApiEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从 OpenAPI 3 文档导入接口说明、依赖扩展（<b>仅作参考</b>，不参与断言）。
 * <p>
 * 各开发文档格式不一，也不能默认与线上一致。导入后供人查阅业务背景、MySQL/Redis、关联接口；
 * 断言只认抓包实测和代码对比。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiImportService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "patch", "delete", "head", "options");

    private final ApiEndpointService endpointService;

    public record ImportResult(int imported, int skipped, List<String> warnings) {
    }

    /**
     * @param project 归属项目 code
     * @param raw     OpenAPI 3 JSON 文本（YAML 请先转成 JSON）
     */
    public ImportResult importDocument(String project, String raw) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("要指定归属项目");
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("文档内容为空");
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("不是合法的 OpenAPI JSON：" + e.getMessage());
        }
        JsonNode paths = root.path("paths");
        if (!paths.isObject() || paths.isEmpty()) {
            throw new IllegalArgumentException("文档里没有 paths");
        }

        int imported = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();

        List<Map.Entry<String, JsonNode>> pathList = new ArrayList<>();
        paths.fields().forEachRemaining(pathList::add);

        for (var pathEntry : pathList) {
            String apiPath = pathEntry.getKey();
            JsonNode methods = pathEntry.getValue();
            if (!methods.isObject()) {
                continue;
            }
            List<Map.Entry<String, JsonNode>> methodList = new ArrayList<>();
            methods.fields().forEachRemaining(methodList::add);
            for (var methodEntry : methodList) {
                String methodKey = methodEntry.getKey().toLowerCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(methodKey)) {
                    continue;
                }
                JsonNode op = methodEntry.getValue();
                try {
                    Map<String, Object> docMeta = buildDocMeta(apiPath, methodKey.toUpperCase(Locale.ROOT), op);
                    ApiEndpoint saved = endpointService.applyDocMeta(
                            project.trim(), methodKey.toUpperCase(Locale.ROOT), apiPath, docMeta);
                    if (saved != null) {
                        imported++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    skipped++;
                    warnings.add(apiPath + " " + methodKey.toUpperCase(Locale.ROOT) + ": " + e.getMessage());
                }
            }
        }

        log.info("[openapi-import] {} 导入 {} 条，跳过 {}", project, imported, skipped);
        return new ImportResult(imported, skipped, warnings);
    }

    private Map<String, Object> buildDocMeta(String path, String httpMethod, JsonNode op) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "openapi");
        meta.put("path", path);
        meta.put("httpMethod", httpMethod);

        String summary = text(op, "summary");
        String description = text(op, "description");
        if (summary != null) {
            meta.put("summary", summary);
        }
        if (description != null) {
            meta.put("description", description);
        }
        String operationId = text(op, "operationId");
        if (operationId != null) {
            meta.put("operationId", operationId);
        }

        List<String> tags = new ArrayList<>();
        if (op.path("tags").isArray()) {
            op.path("tags").forEach(t -> {
                if (t.isTextual() && !t.asText().isBlank()) {
                    tags.add(t.asText().trim());
                }
            });
        }
        if (!tags.isEmpty()) {
            meta.put("tags", tags);
        }

        List<String> fields = responseFieldNames(op);
        if (!fields.isEmpty()) {
            meta.put("fields", fields);
        }

        mergeExtension(meta, op, "x-mysql-tables", "mysqlTables");
        mergeExtension(meta, op, "x-redis-keys", "redisKeys");
        mergeExtension(meta, op, "x-related-paths", "relatedPaths");
        mergeExtension(meta, op, "x-related-apis", "relatedApis");
        mergeExtension(meta, op, "x-dependencies", "dependencies");

        return meta;
    }

    private static void mergeExtension(Map<String, Object> meta, JsonNode op,
                                       String extKey, String metaKey) {
        JsonNode ext = op.get(extKey);
        if (ext == null || ext.isNull()) {
            return;
        }
        if (ext.isArray()) {
            List<Object> list = new ArrayList<>();
            ext.forEach(n -> list.add(jsonToJava(n)));
            if (!list.isEmpty()) {
                meta.put(metaKey, list);
            }
        } else if (ext.isTextual()) {
            meta.put(metaKey, ext.asText());
        } else if (ext.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            ext.fields().forEachRemaining(e -> map.put(e.getKey(), jsonToJava(e.getValue())));
            if (!map.isEmpty()) {
                meta.put(metaKey, map);
            }
        }
    }

    private static Object jsonToJava(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isNumber()) {
            return n.numberValue();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        return n.toString();
    }

    /** 从 200/201 的 application/json 响应 schema 提取顶层字段名 */
    private List<String> responseFieldNames(JsonNode op) {
        Set<String> names = new LinkedHashSet<>();
        JsonNode responses = op.path("responses");
        if (!responses.isObject()) {
            return List.of();
        }
        for (String code : List.of("200", "201", "default")) {
            JsonNode resp = responses.get(code);
            if (resp == null) {
                continue;
            }
            JsonNode content = resp.path("content");
            JsonNode schema = content.path("application/json").path("schema");
            if (schema.isMissingNode()) {
                // 有些文档只写在 */*
                schema = content.path("*/*").path("schema");
            }
            collectSchemaFields(schema, names, 0);
            if (!names.isEmpty()) {
                break;
            }
        }
        return new ArrayList<>(names);
    }

    private void collectSchemaFields(JsonNode schema, Set<String> out, int depth) {
        if (schema == null || schema.isMissingNode() || depth > 4 || out.size() >= 20) {
            return;
        }
        if (schema.has("$ref")) {
            // 无 components 解析时跳过 ref
            return;
        }
        JsonNode props = schema.path("properties");
        if (props.isObject() && !props.isEmpty()) {
            props.fieldNames().forEachRemaining(out::add);
            return;
        }
        JsonNode items = schema.path("items");
        if (items.isObject()) {
            collectSchemaFields(items, out, depth + 1);
        }
        JsonNode allOf = schema.path("allOf");
        if (allOf.isArray()) {
            allOf.forEach(s -> collectSchemaFields(s, out, depth + 1));
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isTextual()) {
            return null;
        }
        String t = v.asText().trim();
        return t.isEmpty() ? null : t;
    }
}
