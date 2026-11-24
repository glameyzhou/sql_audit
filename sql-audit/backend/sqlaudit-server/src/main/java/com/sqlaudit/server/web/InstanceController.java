package com.sqlaudit.server.web;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.IotdbSqlDialect;
import com.sqlaudit.server.service.DataScopeService;
import com.sqlaudit.server.service.JdbcMetadataService;
import com.sqlaudit.storage.dao.InstanceDao;
import com.sqlaudit.storage.model.Instance;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 实例通用端点（契约 §6 实例段；{@code ticket.submit} / {@code query.run} 权限点）。
 *
 * <p>与 {@link ConfigController#instances()}（{@code /api/config/instances}，管理功能，
 * 不受数据范围限制）区分：本控制器的列表与库表元数据浏览按<strong>实例数据范围</strong>
 * 过滤 / 校验（{@code INSTANCE_OUT_OF_SCOPE} 403），不再有环境维度。</p>
 */
@RestController
@RequestMapping("/api/instances")
public class InstanceController {

    private final InstanceDao instanceDao;
    private final JdbcMetadataService metadataService;
    private final DataScopeService dataScopeService;

    public InstanceController(InstanceDao instanceDao, JdbcMetadataService metadataService,
                              DataScopeService dataScopeService) {
        this.instanceDao = instanceDao;
        this.metadataService = metadataService;
        this.dataScopeService = dataScopeService;
    }

    /** 实例列表（数据范围内；管理页维护走 {@code /api/config/instances}）。 */
    @GetMapping
    public List<InstanceRef> list(HttpServletRequest request) {
        DataScopeService.Scope scope = dataScopeService.requestScope(request);
        List<InstanceRef> out = new ArrayList<>();
        for (Instance instance : instanceDao.listAll()) {
            if (scope.covers(instance.getId())) {
                out.add(new InstanceRef(instance.getId(), instance.getName(),
                        instance.getDialect(), instance.getDefaultSchema(),
                        instance.getIotdbSqlDialect()));
            }
        }
        return out;
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
    public List<String> databases(@PathVariable long id, HttpServletRequest request) {
        requireInScope(request, id);
        return metadataService.listDatabases(id);
    }

    /** 某实例某库（PG 语义为 schema）下的表列表（含原生注释：{name, comment}）。 */
    @GetMapping("/{id}/databases/{db}/tables")
    public List<JdbcMetadataService.TableRef> tables(@PathVariable long id, @PathVariable String db,
                                                     HttpServletRequest request) {
        requireInScope(request, id);
        return metadataService.listTables(id, db);
    }

    /**
     * 某实例某库某表的表结构（字段 / 类型 / 可空 / 键 / 默认值 / 注释；
     * {@code table} 走 query 参数，PG 为 {@code schema.table} 限定名，避免点号混入
     * 路径变量被后缀匹配截断）。查询通道只读，SHOW CREATE TABLE / DESC 均被拒，
     * 本端点是唯一的表结构查看入口。
     */
    @GetMapping("/{id}/databases/{db}/table-structure")
    public List<JdbcMetadataService.TableColumn> tableStructure(@PathVariable long id,
                                                                @PathVariable String db,
                                                                @RequestParam String table,
                                                                HttpServletRequest request) {
        requireInScope(request, id);
        return metadataService.tableStructure(id, db, table);
    }

    /**
     * 某实例某库某表的列名清单（按定义顺序；{@code table} 走 query 参数，PG 为
     * {@code schema.table} 限定名，避免点号混入路径变量被后缀匹配截断）。
     */
    @GetMapping("/{id}/databases/{db}/columns")
    public List<String> columns(@PathVariable long id, @PathVariable String db,
                                @RequestParam String table, HttpServletRequest request) {
        requireInScope(request, id);
        return metadataService.listColumns(id, db, table);
    }

    private void requireInScope(HttpServletRequest request, long instanceId) {
        dataScopeService.requireInScope(dataScopeService.requestScope(request), instanceId);
    }

    /** 实例视图（契约 §6：{id, name, dialect, defaultSchema, iotdbSqlDialect}）。 */
    public record InstanceRef(Long id, String name, Dialect dialect, String defaultSchema,
                              IotdbSqlDialect iotdbSqlDialect) {
    }

    /** 连通性测试请求体（方言取值：MYSQL / POSTGRESQL / IOTDB）。 */
    public record TestConnectionRequest(String dialect, String host, Integer port,
                                        String username, String password, String database) {
    }
}
