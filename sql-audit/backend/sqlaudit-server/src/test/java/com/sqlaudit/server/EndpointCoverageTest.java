package com.sqlaudit.server;

import com.sqlaudit.server.test.MySqlItBase;
import com.sqlaudit.storage.dao.PermissionDao;
import com.sqlaudit.storage.model.PermissionApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 构建期端点双向覆盖测试（契约 §4.2 的承重墙）：
 *
 * <ol>
 *   <li><strong>正向</strong>：{@code com.sqlaudit} 包里每个 handler 的每个
 *       （方法 × 处理器模式）都必须在 {@code t_permission_api} 有绑定，否则就是
 *       「已映射但无权限行 = 裸奔」的端点；</li>
 *   <li><strong>反向</strong>：{@code t_permission_api} 的每一行都必须对应真实存在的
 *       端点——重构改名后残留的死行会让端点悄悄变成无人看守；</li>
 *   <li>公开白名单仅 {@code POST /api/auth/login}（拦截器注册处排除）。</li>
 * </ol>
 */
@EnabledIf("com.sqlaudit.server.test.ServerItSupport#mysqlAvailable")
class EndpointCoverageTest extends MySqlItBase {

    /** 公开白名单（不进 t_permission_api，拦截器注册处排除）。 */
    static final Set<String> PUBLIC_WHITELIST = Set.of("POST /api/auth/login");

    @Autowired
    private RequestMappingHandlerMapping mapping;

    @Autowired
    private PermissionDao permissionDao;

    @Test
    void everyHandlerHasBinding() {
        Set<String> handlers = allHandlerKeys();
        Set<String> bound = boundKeys();
        for (String key : handlers) {
            assertTrue(bound.contains(key) || PUBLIC_WHITELIST.contains(key),
                    "端点无权限点绑定（构建期覆盖测试拦截）: " + key);
        }
    }

    @Test
    void everyBindingRowMapsToRealHandler() {
        Set<String> handlers = allHandlerKeys();
        for (PermissionApi api : permissionDao.listAllApis()) {
            String key = api.routeKey();
            assertTrue(handlers.contains(key) || PUBLIC_WHITELIST.contains(key),
                    "t_permission_api 存在指向不存在端点的死绑定: " + key);
        }
    }

    @Test
    void bindingCatalog_nonEmpty() {
        assertTrue(!permissionDao.listAllApis().isEmpty(), "接口绑定目录不应为空");
    }

    /** 展开全部 handler 的（方法 × 模式）笛卡尔积（只看 com.sqlaudit 包）。 */
    private Set<String> allHandlerKeys() {
        Set<String> keys = new HashSet<>();
        Map<RequestMappingInfo, HandlerMethod> methods = mapping.getHandlerMethods();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : methods.entrySet()) {
            HandlerMethod hm = e.getValue();
            if (!hm.getBeanType().getPackageName().startsWith("com.sqlaudit")) {
                continue; // 滤掉框架 / actuator / error 端点
            }
            Set<org.springframework.web.bind.annotation.RequestMethod> httpMethods =
                    e.getKey().getMethodsCondition().getMethods();
            Set<String> patterns = e.getKey().getPathPatternsCondition() == null
                    ? Set.of()
                    : e.getKey().getPathPatternsCondition().getPatternValues();
            for (org.springframework.web.bind.annotation.RequestMethod method : httpMethods) {
                for (String pattern : patterns) {
                    keys.add(method.name() + " " + pattern);
                }
            }
        }
        return keys;
    }

    private Set<String> boundKeys() {
        Set<String> keys = new HashSet<>();
        for (PermissionApi api : permissionDao.listAllApis()) {
            keys.add(api.routeKey());
        }
        return keys;
    }
}
