package com.biggerink.watch.security;

import com.biggerink.watch.common.BizException;
import com.biggerink.watch.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 取当前登录用户；用户端强制资源归属过滤、管理端强制角色校验。
 */
public final class AuthContext {

    private AuthContext() {
    }

    public static LoginUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser u) {
            return u;
        }
        return null;
    }

    public static Long userId() {
        LoginUser u = current();
        if (u == null || u.getUserId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return u.getUserId();
    }

    public static String username() {
        LoginUser u = current();
        return u == null ? "anonymous" : u.getUsername();
    }

    public static boolean isAdmin() {
        LoginUser u = current();
        return u != null && "ADMIN".equals(u.getRole());
    }

    public static void requireAdmin() {
        if (!isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "需要 ADMIN 角色");
        }
    }

    /** 资源归属二次校验：非 ADMIN 且非属主 -> 40301 */
    public static void requireOwner(Long ownerId) {
        if (isAdmin()) {
            return;
        }
        Long uid = userId();
        if (ownerId == null || !ownerId.equals(uid)) {
            throw new BizException(ErrorCode.FORBIDDEN, "资源归属不符");
        }
    }
}
