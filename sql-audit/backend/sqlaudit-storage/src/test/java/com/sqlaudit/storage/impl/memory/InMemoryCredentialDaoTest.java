package com.sqlaudit.storage.impl.memory;

import com.sqlaudit.storage.model.Credential;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryCredentialDao} 单测：按实例+类型查、插入回填 id、按实例列表。
 */
class InMemoryCredentialDaoTest {

    private final InMemoryCredentialDao dao = new InMemoryCredentialDao();

    @Test
    void findByInstanceAndType_hitAndMiss() {
        Credential c = makeCredential(1L, "RW_EXEC", "root", "enc-root");
        dao.insert(c);

        Credential found = dao.findByInstanceAndType(1L, "RW_EXEC");
        assertNotNull(found);
        assertEquals("root", found.getUsername());
        assertEquals("enc-root", found.getPasswordEnc());

        assertNull(dao.findByInstanceAndType(1L, "RO_DATA"), "不同 account_type 不应命中");
        assertNull(dao.findByInstanceAndType(2L, "RW_EXEC"), "不同 instance 不应命中");
    }

    @Test
    void insert_backfillsIdAndDefaults() {
        Credential c = makeCredential(1L, "RW_EXEC", "root", "enc");
        long id = dao.insert(c);

        assertTrue(id > 0);
        assertEquals(id, c.getId());
        assertEquals(1, c.getKeyVersion(), "缺省 key_version 应为 1");
        assertNotNull(c.getCreatedAt());
    }

    @Test
    void listByInstance_orderedByEmptyForOtherInstance() {
        dao.insert(makeCredential(1L, "RW_EXEC", "root", "enc-1"));
        dao.insert(makeCredential(1L, "RO_DATA", "reader", "enc-2"));
        dao.insert(makeCredential(2L, "RW_EXEC", "other", "enc-3"));

        List<Credential> list = dao.listByInstance(1L);
        assertEquals(2, list.size());
        assertTrue(list.get(0).getId() < list.get(1).getId());

        assertEquals(0, dao.listByInstance(999L).size());
    }

    private Credential makeCredential(long instanceId, String type, String username, String enc) {
        Credential c = new Credential();
        c.setInstanceId(instanceId);
        c.setAccountType(type);
        c.setUsername(username);
        c.setPasswordEnc(enc);
        return c;
    }
}
