package com.mobile.standalone.auth;

import com.mobile.standalone.mapper.PlatformUserMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 把最后一个管理员降级、停用或删掉之后，账号管理页就再也进不去了，
 * 而恢复的唯一办法是手改数据库——这正是这套东西要避免的事。
 * 所以这几条守卫比功能本身更值得钉住。
 */
class LastAdminGuardTest {

    private PlatformUser admin(String id, boolean enabled) {
        PlatformUser u = new PlatformUser();
        u.setId(id);
        u.setUsername(id);
        u.setRole(PlatformRole.ADMIN.name());
        u.setEnabled(enabled);
        return u;
    }

    private PlatformUserService serviceWith(PlatformUser found, int activeAdmins) {
        PlatformUserMapper mapper = mock(PlatformUserMapper.class);
        when(mapper.findById(anyString())).thenReturn(found);
        when(mapper.countActiveAdmins()).thenReturn(activeAdmins);
        return new PlatformUserService(mapper, null);
    }

    @Test
    void 最后一个管理员不能降级() {
        PlatformUserService svc = serviceWith(admin("a1", true), 1);

        assertThatThrownBy(() -> svc.update("a1", null, PlatformRole.TESTER.name(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最后一个启用中的管理员");
    }

    @Test
    void 最后一个管理员不能停用() {
        PlatformUserService svc = serviceWith(admin("a1", true), 1);

        assertThatThrownBy(() -> svc.update("a1", null, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最后一个启用中的管理员");
    }

    @Test
    void 最后一个管理员不能删除() {
        PlatformUserService svc = serviceWith(admin("a1", true), 1);

        assertThatThrownBy(() -> svc.delete("a1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最后一个启用中的管理员");
    }

    /** 还有第二个管理员时就没这个限制了，否则管理员会变成删不掉的僵尸账号 */
    @Test
    void 还有别的管理员时可以降级() {
        PlatformUserService svc = serviceWith(admin("a1", true), 2);

        assertThatCode(() -> svc.update("a1", null, PlatformRole.TESTER.name(), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> svc.delete("a1")).doesNotThrowAnyException();
    }

    /** 改显示名不涉及权限变化，不该被守卫挡住 */
    @Test
    void 改显示名不受限制() {
        PlatformUserService svc = serviceWith(admin("a1", true), 1);

        assertThatCode(() -> svc.update("a1", "杨工", null, null)).doesNotThrowAnyException();
    }

    /** 已经停用的管理员不算「启用中」，删它不该被拦 */
    @Test
    void 已停用的管理员可以删() {
        PlatformUserService svc = serviceWith(admin("a1", false), 1);

        assertThatCode(() -> svc.delete("a1")).doesNotThrowAnyException();
    }
}
