package com.sqlaudit.server.web;

import com.sqlaudit.server.auth.RequireRole;
import com.sqlaudit.server.service.JdbcMetadataService;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.model.Instance;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 实例通用端点（所有登录用户可用：提交变更、线上查询等链路需要）。
 *
 * <p>与 {@link ConfigController#instances()}（{@code /api/config/instances}，仅 ADMIN
 * 管理页维护）区分：本控制器不涉及写操作与配置变更，只提供实例列表 / 连通性测试 /
 * 库表元数据浏览。连通性与元数据查询的 JDBC 逻辑收敛在 {@link JdbcMetadataService}，
 * 控制器保持薄。</p>
 */
@RestController
@RequestMapping("/api/instances")
@RequireRole({"ADMIN", "DBA", "LEAD", "DEV"})
public class InstanceController {

    private final InstanceDao instanceDao;
    private final JdbcMetadataService metadataService;

    public InstanceController(InstanceDao instanceDao, JdbcMetadataService metadataService) {
        this.instanceDao = instanceDao;
        this.metadataService = metadataService;
    }

    /** 实例列表（所有登录用户；管理页维护走 {@code /api/config/instances}）。 */
    @GetMapping
    public List<Instance> list() {
        return instanceDao.listAll();
    }

    /**
     * 连通性测试：成功 {@code {ok:true, message:"连接成功"}}；失败一律
     * {@code {ok:false, message:简短原因}}（IoTDB 提示暂不支持），绝不抛 500。
     */
    @PostMapping("/test-connection")
    public Map<String, Object> testConnection(@RequestBody TestConnectionRequest req) {
        return metadataService.testConnection(req.dialect(), req.host(), req.port(),
                req.username(), req.password(), req.database());
    }

    /** 某实例下的库列表（经该实例 RO_META 只读凭据连生产库）。 */
    @GetMapping("/{id}/databases")
    public List<String> databases(@PathVariable long id) {
        return metadataService.listDatabases(id);
    }

    /** 某实例某库（PG 语义为 schema）下的表列表。 */
    @GetMapping("/{id}/databases/{db}/tables")
    public List<String> tables(@PathVariable long id, @PathVariable String db) {
        return metadataService.listTables(id, db);
    }

    /** 连通性测试请求体（方言取值：MYSQL / POSTGRESQL / IOTDB）。 */
    public record TestConnectionRequest(String dialect, String host, Integer port,
                                        String username, String password, String database) {
    }
}
