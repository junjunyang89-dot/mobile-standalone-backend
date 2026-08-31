package com.mobile.standalone.service;

import com.mobile.standalone.entity.ProjectModule;
import com.mobile.standalone.mapper.ProjectModuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 功能模块树的维护。
 * <p>
 * 模块按产品线（分类）维护，项目共用一棵——两端功能一样，不该各存一份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectModuleService {

    /** 再深就不像功能模块而像页面路径了；三层足够「壳 → 子壳 → 具体功能」 */
    private static final int MAX_DEPTH = 3;

    private final ProjectModuleMapper mapper;
    private final ApiProjectService projectService;

    /** 平铺列表，供下拉选择 */
    public List<ProjectModule> list(String category, Boolean enabledOnly) {
        return mapper.findByCategory(category, enabledOnly);
    }

    /**
     * 组装成树。
     * <p>
     * 在内存里拼而不是递归查库：模块量级是几十到几百，一次全取再组装，
     * 比逐层查快得多，对着公网远程库尤其明显。
     */
    public List<ProjectModule> tree(String category, Boolean enabledOnly) {
        List<ProjectModule> all = mapper.findByCategory(category, enabledOnly);
        Map<String, ProjectModule> byId = new LinkedHashMap<>();
        for (ProjectModule m : all) {
            m.setChildren(new ArrayList<>());
            byId.put(m.getId(), m);
        }
        List<ProjectModule> roots = new ArrayList<>();
        for (ProjectModule m : all) {
            ProjectModule parent = m.getParentId() == null ? null : byId.get(m.getParentId());
            if (parent == null) {
                // 父节点被过滤掉了（比如父停用而子启用）也当顶层挂出来，
                // 不然这个模块会整个从树上消失，人只会以为数据丢了
                roots.add(m);
            } else {
                parent.getChildren().add(m);
            }
        }
        return roots;
    }

    @Transactional
    public ProjectModule save(ProjectModule body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        String name = trim(body.getName());
        if (name.isEmpty()) {
            throw new IllegalArgumentException("模块名称不能为空");
        }
        String category = trim(body.getCategory()).toUpperCase(Locale.ROOT);
        String parentId = trim(body.getParentId());
        boolean isNew = trim(body.getId()).isEmpty();

        // 自引用检查必须排在父模块查找【之前】：把自己设为父是逻辑错误，
        // 和「这个 id 在库里查不查得到」无关。放在后面的话，报出来的会是
        // 「父模块不存在」，把人往完全错的方向指
        if (!isNew && parentId.equals(trim(body.getId()))) {
            throw new IllegalArgumentException("模块不能把自己设为父模块");
        }

        ProjectModule parent = null;
        if (!parentId.isEmpty()) {
            parent = mapper.findById(parentId);
            if (parent == null) {
                throw new IllegalArgumentException("父模块不存在: " + parentId);
            }
            // 子模块的分类跟随父模块，不接受调用方另填一个——
            // 否则会出现父在 APP 下、子在 POGPOG 下的断裂节点
            category = parent.getCategory();
            if (depthOf(parent) + 1 >= MAX_DEPTH) {
                throw new IllegalArgumentException("模块层级最多 " + MAX_DEPTH + " 层，不能再往下加了");
            }
        } else if (category.isEmpty()) {
            throw new IllegalArgumentException("顶层模块必须指定所属分类");
        } else if (!projectService.categoryExists(category)) {
            throw new IllegalArgumentException("分类不存在: " + category + "，请先到「项目管理」创建");
        }

        if (!isNew && !parentId.isEmpty() && isDescendant(parentId, trim(body.getId()))) {
            // 不拦的话会形成环，tree() 组装时那一支会整个从树上消失
            throw new IllegalArgumentException("不能把模块挂到它自己的子模块下，那会形成循环");
        }

        String excludeId = isNew ? null : trim(body.getId());
        if (mapper.findSibling(category, parentId.isEmpty() ? null : parentId, name, excludeId) != null) {
            throw new IllegalArgumentException("同一层级下已有同名模块: " + name);
        }

        body.setCategory(category);
        body.setName(name);
        body.setParentId(parentId.isEmpty() ? null : parentId);
        if (body.getSortOrder() == null) {
            body.setSortOrder(100);
        }
        if (body.getEnabled() == null) {
            body.setEnabled(true);
        }
        if (isNew) {
            body.setId("pm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            mapper.insert(body);
        } else {
            mapper.update(body);
        }
        return mapper.findById(body.getId());
    }

    /**
     * 删除。
     * <p>
     * 有子模块时拒绝，而不是靠外键的 CASCADE 悄悄连着删掉一整棵子树——
     * 那种「点一下没了十几个模块」的体验没法接受。
     */
    @Transactional
    public boolean delete(String id) {
        ProjectModule m = mapper.findById(id);
        if (m == null) {
            return false;
        }
        int children = mapper.countChildren(id);
        if (children > 0) {
            throw new IllegalArgumentException(
                    "该模块下还有 " + children + " 个子模块，请先处理它们");
        }
        if (m.getRequirementCount() != null && m.getRequirementCount() > 0) {
            throw new IllegalArgumentException(
                    "该模块已被 " + m.getRequirementCount() + " 个需求引用，不能直接删除；"
                            + "确实不用了可以先停用");
        }
        return mapper.deleteById(id) > 0;
    }

    private int depthOf(ProjectModule node) {
        int depth = 0;
        ProjectModule cur = node;
        while (cur != null && cur.getParentId() != null && depth < MAX_DEPTH + 1) {
            cur = mapper.findById(cur.getParentId());
            depth++;
        }
        return depth;
    }

    /** candidate 是否在 ancestorId 的子孙链上 */
    private boolean isDescendant(String candidateId, String ancestorId) {
        ProjectModule cur = mapper.findById(candidateId);
        int guard = 0;
        while (cur != null && cur.getParentId() != null && guard++ < MAX_DEPTH + 2) {
            if (ancestorId.equals(cur.getParentId())) {
                return true;
            }
            cur = mapper.findById(cur.getParentId());
        }
        return false;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
