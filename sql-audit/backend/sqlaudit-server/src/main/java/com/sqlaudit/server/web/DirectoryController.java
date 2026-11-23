package com.sqlaudit.server.web;

import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.Role;
import com.sqlaudit.storage.model.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 人员 / 角色目录端点（契约 §6 目录段；{@code basic}（all_users）权限点——所有登录用户可用）。
 *
 * <p>给审批流节点编辑器选人 / 选角色、实例负责人下拉、转交选人、用户分配角色下拉用。
 * 只回显必要字段（用户仅 ACTIVE），不含敏感信息。</p>
 */
@RestController
@RequestMapping("/api/directory")
public class DirectoryController {

    private final UserDao userDao;
    private final RoleDao roleDao;

    public DirectoryController(UserDao userDao, RoleDao roleDao) {
        this.userDao = userDao;
        this.roleDao = roleDao;
    }

    /** 全部 ACTIVE 用户（选人下拉）。 */
    @GetMapping("/users")
    public List<UserRef> users() {
        List<UserRef> out = new ArrayList<>();
        for (User u : userDao.listActive()) {
            out.add(new UserRef(u.getId(), u.getUsername(), u.getDisplayName()));
        }
        return out;
    }

    /** 全部角色（选角色下拉）。 */
    @GetMapping("/roles")
    public List<RoleRef> roles() {
        List<RoleRef> out = new ArrayList<>();
        for (Role r : roleDao.listAll()) {
            out.add(new RoleRef(r.getCode(), r.getName()));
        }
        return out;
    }

    public record UserRef(long id, String username, String displayName) {
    }

    public record RoleRef(String code, String name) {
    }
}
