package com.mobile.standalone.controller;

import com.mobile.standalone.service.ProjectTreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 统一树。
 * <p>
 * 收敛原先三个各自为政的树端点（{@code /api-projects/tree}、
 * {@code /api-test/collections/tree}、{@code /api-endpoints/tree}）：
 * 节点结构一致，前端一套渲染。旧端点暂时保留，等前端切完再下线。
 */
@RestController
@RequestMapping("/tree")
@RequiredArgsConstructor
public class TreeController {

    private final ProjectTreeService projectTreeService;

    /**
     * 分类 → 项目 → 模块，每层带 counts。
     *
     * @param dimension  目前仅支持 {@code project}；参数先留着，后续接入别的维度时不用改 URL
     * @param includeEmpty 默认 true。「这个项目一条用例都没有」正是要看见的信息，
     *                     默认藏起来等于把欠账藏起来
     */
    @GetMapping
    public ResponseEntity<?> tree(
            @RequestParam(required = false, defaultValue = "project") String dimension,
            @RequestParam(required = false, defaultValue = "true") boolean includeEmpty) {
        if (!"project".equalsIgnoreCase(dimension)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "暂不支持的维度: " + dimension + "（目前仅 project）"));
        }
        return ResponseEntity.ok(projectTreeService.build(includeEmpty));
    }
}
