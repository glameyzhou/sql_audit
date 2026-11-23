package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.User;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link UserDao} 的内存 fake 实现（供 auth 单测与本地联调解耦真实数据库）。
 *
 * <p>语义对齐接口 javadoc：{@code insert} 回填自增 id；{@code findByUsername} 未命中
 * 返回 {@code null}；{@code updateLastLoginAt} 不存在则影响行数 0。查询返回快照拷贝。</p>
 */
public class InMemoryUserDao implements UserDao {

    private final Map<Long, User> store = new ConcurrentHashMap<>();
    private final Map<String, Long> usernameToId = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    @Override
    public synchronized long insert(User user) {
        long id = idGenerator.incrementAndGet();
        user.setId(id);
        LocalDateTime now = LocalDateTime.now();
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(now);
        }
        if (user.getUpdatedAt() == null) {
            user.setUpdatedAt(now);
        }
        store.put(id, copy(user));
        usernameToId.put(user.getUsername(), id);
        return id;
    }

    @Override
    public synchronized User findByUsername(String username) {
        Long id = usernameToId.get(username);
        if (id == null) {
            return null;
        }
        User user = store.get(id);
        return user == null ? null : copy(user);
    }

    @Override
    public synchronized User findById(long id) {
        User user = store.get(id);
        return user == null ? null : copy(user);
    }

    @Override
    public synchronized List<User> listAll() {
        return store.values().stream()
                .sorted(Comparator.comparingLong(User::getId))
                .map(InMemoryUserDao::copy)
                .toList();
    }

    @Override
    public synchronized int updatePassword(long id, String passwordHash) {
        User user = store.get(id);
        if (user == null) {
            return 0;
        }
        user.setPasswordHash(passwordHash);
        user.setUpdatedAt(LocalDateTime.now());
        return 1;
    }

    @Override
    public synchronized int updateStatus(long id, UserStatus status) {
        User user = store.get(id);
        if (user == null) {
            return 0;
        }
        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        return 1;
    }

    @Override
    public synchronized int updateProfile(long id, String displayName, String email) {
        User user = store.get(id);
        if (user == null) {
            return 0;
        }
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setUpdatedAt(LocalDateTime.now());
        return 1;
    }

    @Override
    public synchronized int updateLastLoginAt(long id, LocalDateTime at) {
        User user = store.get(id);
        if (user == null) {
            return 0;
        }
        user.setLastLoginAt(at);
        return 1;
    }

    private static User copy(User src) {
        User dest = new User();
        dest.setId(src.getId());
        dest.setUsername(src.getUsername());
        dest.setDisplayName(src.getDisplayName());
        dest.setEmail(src.getEmail());
        dest.setSource(src.getSource());
        dest.setWeworkOpenid(src.getWeworkOpenid());
        dest.setPasswordHash(src.getPasswordHash());
        dest.setTotpSecret(src.getTotpSecret());
        dest.setStatus(src.getStatus());
        dest.setLastLoginAt(src.getLastLoginAt());
        dest.setCreatedAt(src.getCreatedAt());
        dest.setUpdatedAt(src.getUpdatedAt());
        dest.setDeletedAt(src.getDeletedAt());
        return dest;
    }
}
