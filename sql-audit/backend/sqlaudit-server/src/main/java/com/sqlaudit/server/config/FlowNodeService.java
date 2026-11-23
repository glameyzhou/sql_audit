package com.sqlaudit.server.config;

import com.sqlaudit.common.enums.ApprovalMode;
import com.sqlaudit.common.enums.ApproverType;
import com.sqlaudit.common.enums.ErrorCode;
import com.sqlaudit.common.enums.UserStatus;
import com.sqlaudit.common.exception.SqlauditException;
import com.sqlaudit.storage.dao.FlowNodeDao;
import com.sqlaudit.storage.dao.FlowRuleDao;
import com.sqlaudit.storage.dao.RoleDao;
import com.sqlaudit.storage.dao.UserDao;
import com.sqlaudit.storage.model.FlowNode;
import com.sqlaudit.storage.model.FlowRule;
import com.sqlaudit.storage.model.Role;
import com.sqlaudit.storage.model.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 审批流节点配置服务（契约 §5 节点保存校验 + 整体替换）。
 *
 * <p>节点只能<strong>整体替换</strong>（{@link FlowNodeDao#replaceNodes}，删旧 + 插新，
 * 原子）——按单条增删改很难保证「组内 mode 一致 + node_order 从 1 连续」两个不变式。
 * 保存期即失败响亮（Casbin「静默 no-op」的教训）：</p>
 * <ul>
 *   <li>组内 mode 一致（同 nodeOrder 的所有节点 mode 相同）；</li>
 *   <li>node_order 从 1 连续（集合恰为 {1..n}）；</li>
 *   <li>ROLE 节点：角色存在且至少有 ACTIVE 成员；</li>
 *   <li>USER 节点：用户存在且 ACTIVE；</li>
 *   <li>INSTANCE_OWNER 节点：ref 恒为 {@code *}。</li>
 * </ul>
 *
 * <p>保存成功后调用 {@link FlowConfigLoader#reload()} 即时生效（提交时引擎还会按当时
 * 状态再校验一次）。</p>
 */
@Service
public class FlowNodeService {

    private final FlowNodeDao flowNodeDao;
    private final FlowRuleDao flowRuleDao;
    private final RoleDao roleDao;
    private final UserDao userDao;
    private final FlowConfigLoader flowConfigLoader;

    public FlowNodeService(FlowNodeDao flowNodeDao, FlowRuleDao flowRuleDao, RoleDao roleDao,
                           UserDao userDao, FlowConfigLoader flowConfigLoader) {
        this.flowNodeDao = flowNodeDao;
        this.flowRuleDao = flowRuleDao;
        this.roleDao = roleDao;
        this.userDao = userDao;
        this.flowConfigLoader = flowConfigLoader;
    }

    /** 整体替换某流程的节点（校验失败抛 {@code FLOW_NODE_INVALID} 400，不做任何写入）。 */
    @Transactional
    public void replaceNodes(long flowRuleId, List<Request> requests) {
        FlowRule flow = flowRuleDao.findById(flowRuleId);
        if (flow == null) {
            throw new SqlauditException(ErrorCode.FLOW_NOT_FOUND, "审批流不存在: " + flowRuleId);
        }
        List<FlowNode> nodes = validate(requests);
        flowNodeDao.replaceNodes(flowRuleId, nodes);
        flowConfigLoader.reload();
    }

    /** 请求 → 实体 + 校验（不落库）。 */
    public List<FlowNode> validate(List<Request> requests) {
        List<Request> list = requests == null ? List.of() : requests;
        if (list.isEmpty()) {
            return List.of(); // 空节点 = 该流程免审
        }

        // node_order 从 1 连续 + 组内 mode 一致
        TreeSet<Integer> orders = new TreeSet<>();
        Map<Integer, ApprovalMode> modeByGroup = new HashMap<>();
        for (Request r : list) {
            Integer order = r.nodeOrder();
            if (order == null || order < 1) {
                throw new SqlauditException(ErrorCode.FLOW_NODE_INVALID,
                        "node_order 必须为 ≥1 的整数");
            }
            orders.add(order);
            ApprovalMode existing = modeByGroup.get(order);
            if (existing != null && r.mode() != existing) {
                throw new SqlauditException(ErrorCode.FLOW_NODE_INVALID,
                        "同一组（node_order=" + order + "）内的 mode 必须一致");
            }
            modeByGroup.put(order, r.mode());
        }
        int expected = 1;
        for (Integer order : orders) {
            if (order != expected) {
                throw new SqlauditException(ErrorCode.FLOW_NODE_INVALID,
                        "node_order 必须从 1 连续（缺 " + expected + "）");
            }
            expected++;
        }

        // 引用对象校验（保存期失败响亮，不等工单卡死才发现）
        List<FlowNode> nodes = new ArrayList<>(list.size());
        for (Request r : list) {
            FlowNode node = new FlowNode();
            node.setNodeOrder(r.nodeOrder());
            node.setApproverType(r.approverType());
            node.setApproverRef(r.approverRef());
            node.setMode(r.mode());
            validateRef(node);
            nodes.add(node);
        }
        return nodes;
    }

    private void validateRef(FlowNode node) {
        if (node.getApproverType() == null || node.getMode() == null) {
            throw new SqlauditException(ErrorCode.FLOW_NODE_INVALID,
                    "节点类型与 mode 不能为空");
        }
        String ref = node.getApproverRef();
        switch (node.getApproverType()) {
            case USER -> {
                long userId = parseUserId(ref);
                User user = userDao.findById(userId);
                if (user == null || user.getStatus() != UserStatus.ACTIVE) {
                    throw new SqlauditException(ErrorCode.FLOW_NODE_INVALID,
                            "USER 节点引用的用户不存在或已停用: " + ref);
                }
            }
            case ROLE -> {
                if (ref == null || ref.isBlank()) {
                    throw new SqlauditException(ErrorCode.FLOW_NODE_INVALID, "ROLE 节点缺少角色 code");
                }
                Role role = roleDao.findByCode(ref);
                if (role == null || !roleDao.hasActiveMember(role.getId(), null)) {
                    throw new SqlauditException(ErrorCode.FLOW_NODE_INVALID,
                            "ROLE 节点引用的角色不存在或无有效成员: " + ref);
                }
            }
            case INSTANCE_OWNER -> {
                if (!FlowNode.INSTANCE_OWNER_REF.equals(ref)) {
                    throw new SqlauditException(ErrorCode.FLOW_NODE_INVALID,
                            "INSTANCE_OWNER 节点的 approver_ref 必须为 \""
                                    + FlowNode.INSTANCE_OWNER_REF + "\"");
                }
            }
        }
    }

    private static long parseUserId(String ref) {
        try {
            return Long.parseLong(ref);
        } catch (NumberFormatException e) {
            throw new SqlauditException(ErrorCode.FLOW_NODE_INVALID,
                    "USER 节点引用的用户 id 非法: " + ref);
        }
    }

    /** 节点保存请求（契约 §6 审批流配置段）。 */
    public record Request(Integer nodeOrder, ApproverType approverType, String approverRef,
                          ApprovalMode mode) {
    }
}
