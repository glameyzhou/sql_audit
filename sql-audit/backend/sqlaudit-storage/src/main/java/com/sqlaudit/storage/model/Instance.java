package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.InstanceStatus;
import com.sqlaudit.common.enums.IotdbSqlDialect;
import java.time.LocalDateTime;

/**
 * 实例实体（对应 t_instance 表，生产实例与凭据模型）。
 *
 * <p>唯一写入方：sqlaudit-server（系统配置 CRUD）。删除为软删（置 deleted_at），
 * 列表/查询默认过滤已删除实例。</p>
 */
public class Instance {

    private Long id;
    /** 实例名 */
    private String name;
    /** MYSQL / PG / IOTDB */
    private Dialect dialect;
    /** 实例负责人（用户 id）；审批链 INSTANCE_OWNER 节点解析到本人 */
    private Long ownerId;
    private String host;
    private Integer port;
    /** 默认库；IoTDB 存 storage group 路径 */
    private String defaultSchema;
    /** IoTDB SQL 方言（TREE/TABLE）；仅 IOTDB 实例有意义，非 IOTDB 为 null（null 按 TREE） */
    private IotdbSqlDialect iotdbSqlDialect;
    /** ACTIVE / DISABLED */
    private InstanceStatus status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Dialect getDialect() {
        return dialect;
    }

    public void setDialect(Dialect dialect) {
        this.dialect = dialect;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDefaultSchema() {
        return defaultSchema;
    }

    public void setDefaultSchema(String defaultSchema) {
        this.defaultSchema = defaultSchema;
    }

    public IotdbSqlDialect getIotdbSqlDialect() {
        return iotdbSqlDialect;
    }

    public void setIotdbSqlDialect(IotdbSqlDialect iotdbSqlDialect) {
        this.iotdbSqlDialect = iotdbSqlDialect;
    }

    public InstanceStatus getStatus() {
        return status;
    }

    public void setStatus(InstanceStatus status) {
        this.status = status;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
