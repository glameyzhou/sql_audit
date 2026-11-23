package com.sqlaudit.workflow;

import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.Instance;
import com.sqlaudit.storage.model.Role;
import com.sqlaudit.storage.model.User;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * workflow 单测用的最小内存替身（storage 的 InMemory{User,Role,Instance}Dao 已随
 * memory profile 删除，这里只实现引擎用到的读路径；写方法抛
 * {@link UnsupportedOperationException} 防止误用）。
 *
 * <p>引擎（{@link ApprovalFlowEngine}）只读：{@code UserDao.findById} /
 * {@code RoleDao.findByCode + hasActiveMember + listByUserId} / {@code InstanceDao.findById}。</p>
 */
final class WorkflowTestDoubles {

    private WorkflowTestDoubles() {
    }

    /** 用户内存替身。 */
    static final class FakeUserDao implements UserDao {
        private final Map<Long, User> users = new LinkedHashMap<>();
        private long seq = 0;

        long add(String username, UserStatus status) {
            return add(++seq, username, status);
        }

        long add(long id, String username, UserStatus status) {
            User u = new User();
            u.setId(id);
            u.setUsername(username);
            u.setDisplayName(username);
            u.setStatus(status);
            u.setCreatedAt(LocalDateTime.now());
            u.setUpdatedAt(LocalDateTime.now());
            users.put(u.getId(), u);
            return u.getId();
        }

        void setStatus(long id, UserStatus status) {
            users.get(id).setStatus(status);
        }

        @Override
        public User findByUsername(String username) {
            return users.values().stream()
                    .filter(u -> u.getUsername().equals(username)).findFirst().orElse(null);
        }

        @Override
        public User findById(long id) {
            return users.get(id);
        }

        @Override
        public List<User> listAll() {
            return new ArrayList<>(users.values());
        }

        @Override
        public List<User> listActive() {
            return users.values().stream()
                    .filter(u -> u.getStatus() == UserStatus.ACTIVE).toList();
        }

        @Override
        public long insert(User user) {
            throw new UnsupportedOperationException("workflow 单测不写用户");
        }

        @Override
        public int updateLastLoginAt(long id, LocalDateTime at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updatePassword(long id, String passwordHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateStatus(long id, UserStatus status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateProfile(long id, String displayName, String email) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countActiveMetaPermissionHolders() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Long> lockMetaPermissions() {
            throw new UnsupportedOperationException();
        }
    }

    /** 角色内存替身（角色 + 成员，只读路径）。 */
    static final class FakeRoleDao implements RoleDao {
        private final Map<Long, Role> roles = new LinkedHashMap<>();
        /** roleId → 成员 userId 列表 */
        private final Map<Long, List<Long>> members = new LinkedHashMap<>();
        /** 成员状态的来源（hasActiveMember 判定用）。 */
        private final FakeUserDao users;
        private long seq = 0;

        FakeRoleDao(FakeUserDao users) {
            this.users = users;
        }

        long addRole(String code) {
            Role r = new Role();
            r.setId(++seq);
            r.setCode(code);
            r.setName(code);
            r.setBuiltin(true);
            r.setCreatedAt(LocalDateTime.now());
            r.setUpdatedAt(LocalDateTime.now());
            roles.put(r.getId(), r);
            members.put(r.getId(), new ArrayList<>());
            return r.getId();
        }

        void addMember(String roleCode, long userId) {
            Role r = findByCode(roleCode);
            if (r == null) {
                throw new IllegalArgumentException("角色不存在: " + roleCode);
            }
            members.get(r.getId()).add(userId);
        }

        @Override
        public List<Role> listAll() {
            return new ArrayList<>(roles.values());
        }

        @Override
        public Role findById(long id) {
            return roles.get(id);
        }

        @Override
        public Role findByCode(String code) {
            return roles.values().stream().filter(r -> r.getCode().equals(code))
                    .findFirst().orElse(null);
        }

        @Override
        public long insert(Role role) {
            throw new UnsupportedOperationException("workflow 单测不写角色");
        }

        @Override
        public int update(Role role) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int delete(long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Role> listByUserId(long userId) {
            List<Role> result = new ArrayList<>();
            members.forEach((roleId, userIds) -> {
                if (userIds.contains(userId)) {
                    result.add(roles.get(roleId));
                }
            });
            result.sort((a, b) -> Long.compare(a.getId(), b.getId()));
            return result;
        }

        @Override
        public int countUsers(long roleId) {
            return members.getOrDefault(roleId, List.of()).size();
        }

        @Override
        public boolean hasActiveMember(long roleId, Long excludeUserId) {
            for (Long userId : members.getOrDefault(roleId, List.of())) {
                if (excludeUserId != null && excludeUserId.equals(userId)) {
                    continue;
                }
                User u = users.findById(userId);
                if (u != null && u.getStatus() == UserStatus.ACTIVE) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void replaceUserRoles(long userId, List<Long> roleIds, Long grantedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Long> listPermissionIds(long roleId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replacePermissions(long roleId, List<Long> permissionIds, Long grantedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Long> listInstanceIds(long roleId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceInstances(long roleId, List<Long> instanceIds, Long grantedBy) {
            throw new UnsupportedOperationException();
        }
    }

    /** 实例内存替身（只读）。 */
    static final class FakeInstanceDao implements InstanceDao {
        private final Map<Long, Instance> instances = new LinkedHashMap<>();

        long add(long id, long ownerId) {
            Instance inst = new Instance();
            inst.setId(id);
            inst.setName("instance-" + id);
            inst.setOwnerId(ownerId);
            inst.setHost("127.0.0.1");
            inst.setPort(3306);
            inst.setCreatedBy(1L);
            inst.setCreatedAt(LocalDateTime.now());
            inst.setUpdatedAt(LocalDateTime.now());
            instances.put(id, inst);
            return id;
        }

        @Override
        public List<Instance> listAll() {
            return new ArrayList<>(instances.values());
        }

        @Override
        public Instance findById(long id) {
            return instances.get(id);
        }

        @Override
        public Instance findByName(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long insert(Instance instance) {
            throw new UnsupportedOperationException("workflow 单测不写实例");
        }

        @Override
        public int update(Instance instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int delete(long id) {
            throw new UnsupportedOperationException();
        }
    }
}
