package com.mobile.standalone.service;

import com.mobile.standalone.entity.ProjectModule;
import com.mobile.standalone.mapper.ProjectModuleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectModuleServiceTest {

    @Mock
    private ProjectModuleMapper mapper;
    @Mock
    private ApiProjectService projectService;

    @InjectMocks
    private ProjectModuleService service;

    private static ProjectModule module(String id, String category, String parentId, String name) {
        ProjectModule m = new ProjectModule();
        m.setId(id);
        m.setCategory(category);
        m.setParentId(parentId);
        m.setName(name);
        return m;
    }

    @Test
    void tree_nestsChildrenUnderParents() {
        when(mapper.findByCategory(anyString(), any())).thenReturn(List.of(
                module("m1", "APP", null, "数据壳"),
                module("m2", "APP", "m1", "DOTA2 数据壳"),
                module("m3", "APP", null, "赛事壳")));

        List<ProjectModule> roots = service.tree("APP", null);

        assertThat(roots).hasSize(2);
        ProjectModule shell = roots.stream()
                .filter(m -> "数据壳".equals(m.getName())).findFirst().orElseThrow();
        assertThat(shell.getChildren()).extracting(ProjectModule::getName)
                .containsExactly("DOTA2 数据壳");
    }

    /** 父被过滤掉（比如父停用而子启用）时，子仍要当顶层挂出来，不能整个消失 */
    @Test
    void tree_promotesOrphanToRootWhenParentFilteredOut() {
        when(mapper.findByCategory(anyString(), any())).thenReturn(List.of(
                module("m2", "APP", "missing-parent", "孤儿模块")));

        List<ProjectModule> roots = service.tree("APP", true);

        assertThat(roots).extracting(ProjectModule::getName).containsExactly("孤儿模块");
    }

    @Test
    void save_rejectsDuplicateNameUnderSameParent() {
        when(projectService.categoryExists("APP")).thenReturn(true);
        when(mapper.findSibling(anyString(), isNull(), anyString(), isNull()))
                .thenReturn(module("existing", "APP", null, "数据壳"));

        assertThatThrownBy(() -> service.save(module(null, "APP", null, "数据壳")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同名模块");
        verify(mapper, never()).insert(any());
    }

    /** 子模块的分类跟随父模块，避免出现父在 APP、子在 POGPOG 的断裂节点 */
    @Test
    void save_childInheritsCategoryFromParent() {
        when(mapper.findById("p1")).thenReturn(module("p1", "POGPOG", null, "父"));
        when(mapper.findSibling(anyString(), anyString(), anyString(), isNull())).thenReturn(null);

        service.save(module(null, "APP", "p1", "子"));

        ArgumentCaptor<ProjectModule> saved = ArgumentCaptor.forClass(ProjectModule.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getCategory()).isEqualTo("POGPOG");
    }

    @Test
    void save_rejectsUnknownCategoryForRootModule() {
        when(projectService.categoryExists("NOPE")).thenReturn(false);

        assertThatThrownBy(() -> service.save(module(null, "NOPE", null, "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分类不存在");
    }

    @Test
    void save_rejectsSelfAsParent() {
        assertThatThrownBy(() -> service.save(module("m1", "APP", "m1", "自环")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自己");
    }

    /** 挂到自己的子孙下会成环，tree() 组装时那一支会整个消失 */
    @Test
    void save_rejectsCycleThroughDescendant() {
        when(mapper.findById("child")).thenReturn(module("child", "APP", "root", "子"));
        when(mapper.findById("root")).thenReturn(module("root", "APP", null, "根"));

        assertThatThrownBy(() -> service.save(module("root", "APP", "child", "根")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("循环");
    }

    @Test
    void save_rejectsTooDeepNesting() {
        when(mapper.findById("l3")).thenReturn(module("l3", "APP", "l2", "三层"));
        when(mapper.findById("l2")).thenReturn(module("l2", "APP", "l1", "二层"));
        when(mapper.findById("l1")).thenReturn(module("l1", "APP", null, "一层"));

        assertThatThrownBy(() -> service.save(module(null, "APP", "l3", "四层")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("层级");
    }

    /** 不做静默级联删除：点一下没了一整棵子树的体验不能接受 */
    @Test
    void delete_refusesWhenChildrenOrRequirementsExist() {
        ProjectModule m = module("m1", "APP", null, "数据壳");
        when(mapper.findById("m1")).thenReturn(m);
        when(mapper.countChildren("m1")).thenReturn(3);

        assertThatThrownBy(() -> service.delete("m1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("子模块");

        when(mapper.countChildren("m1")).thenReturn(0);
        m.setRequirementCount(2);
        assertThatThrownBy(() -> service.delete("m1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需求引用");
        verify(mapper, never()).deleteById(anyString());
    }
}
