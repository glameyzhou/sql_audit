package com.sqlaudit.server;

import com.sqlaudit.worker.WorkerLoop;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * memory（默认）profile 下 worker 循环不启动的验证。
 *
 * <p>内存方言缺 {@code ExecLockDao} / 平台库 {@code JdbcTemplate}，worker 全套 bean
 * 应被 {@code @Profile} 门禁拦住，{@link WorkerLoop} 不得装配——单测环境不被执行循环抢跑污染。</p>
 */
@SpringBootTest
class WorkerMergeMemoryProfileTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void workerLoopNotAssembled_inMemoryProfile() {
        WorkerLoop loop = context.getBeanProvider(WorkerLoop.class).getIfAvailable();
        assertNull(loop, "memory（默认）profile 下不应装配 WorkerLoop");
    }
}
