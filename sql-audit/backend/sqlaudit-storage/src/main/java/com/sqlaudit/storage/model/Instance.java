package com.sqlaudit.storage.model;

import com.sqlaudit.common.enums.Dialect;
import com.sqlaudit.common.enums.Environment;
import com.sqlaudit.common.enums.InstanceStatus;
import java.time.LocalDateTime;

/**
 * 实例实体（对应 t_instance 表，设计文档 §4 实例与凭据）。
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
    /** prod / staging */
    private Environment environment;
    /** 实例 Owner（用户 id），自动进入该实例高危审批链 */
    private Long ownerId;
    private String host;
    private Integer port;
    /** 默认库；IoTDB 存 storage group 路径 */
    private String defaultSchema;
    /** 只读副本 DSN；空则按降级策略 */
    private String replicaDsn;
    /** 指向 credential 组 */
    private Long credentialId;
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

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
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

    public String getReplicaDsn() {
        return replicaDsn;
    }

    public void setReplicaDsn(String replicaDsn) {
        this.replicaDsn = replicaDsn;
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(Long credentialId) {
        this.credentialId = credentialId;
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
