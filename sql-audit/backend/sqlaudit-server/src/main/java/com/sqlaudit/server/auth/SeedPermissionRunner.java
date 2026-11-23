package com.sqlaudit.server.auth;

import com.sqlaudit.common.enums.PermissionCode;
import com.sqlaudit.storage.dao.PermissionDao;
import com.sqlaudit.storage.model.Permission;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 权限点目录种子：启动时把 {@link PermissionCode} 枚举同步进 {@code t_permission}
 * （insert-if-absent）。目录以枚举为唯一真源，DB 里只补不删不覆盖——名称/说明改了以后
 * 老行保留旧文案（如需回填可后续加 update）。
 *
 * <p>内存方言：{@code InMemoryPermissionDao} 构造时已预置目录，本 runner 会因
 * {@code findByCode != null} 全部跳过，幂等。</p>
 */
@Component
public class SeedPermissionRunner implements ApplicationRunner {

    private final PermissionDao permissionDao;

    public SeedPermissionRunner(PermissionDao permissionDao) {
        this.permissionDao = permissionDao;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (PermissionCode pc : PermissionCode.values()) {
            if (permissionDao.findByCode(pc.getCode()) == null) {
                Permission p = new Permission();
                p.setCode(pc.getCode());
                p.setName(pc.getDisplayName());
                p.setDescription(pc.getDescription());
                p.setCustomized(false);
                permissionDao.insert(p);
            }
        }
    }
}
