package com.biggerink.watch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biggerink.watch.common.ApiDtos;
import com.biggerink.watch.common.BizException;
import com.biggerink.watch.common.ErrorCode;
import com.biggerink.watch.entity.UserAccount;
import com.biggerink.watch.mapper.UserAccountMapper;
import com.biggerink.watch.security.AuthContext;
import com.biggerink.watch.security.JwtUtil;
import com.biggerink.watch.security.LoginUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class AuthService {

    private final UserAccountMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RedisOps redis;
    private final MetaService meta;

    @Value("${watch.default-quota-subscription:20}")
    private int defaultQuotaSubscription;

    @Value("${watch.default-quota-scan-daily:288}")
    private int defaultQuotaScanDaily;

    public AuthService(UserAccountMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       RedisOps redis,
                       MetaService meta) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.redis = redis;
        this.meta = meta;
    }

    public ApiDtos.UserVO register(ApiDtos.RegisterReq req) {
        Long byName = userMapper.selectCount(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, req.getUsername()));
        if (byName != null && byName > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户名已存在");
        }
        Long byEmail = userMapper.selectCount(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getEmail, req.getEmail()));
        if (byEmail != null && byEmail > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "邮箱已存在");
        }
        UserAccount user = new UserAccount();
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setRole("USER");
        user.setStatus("NORMAL");
        user.setQuotaSubscription(defaultQuotaSubscription);
        user.setQuotaScanDaily(defaultQuotaScanDaily);
        userMapper.insert(user);
        meta.audit(user.getId(), "AUTH_REGISTER", "user", String.valueOf(user.getId()), req.getEmail());
        return toVO(user);
    }

    public ApiDtos.TokenVO login(ApiDtos.LoginReq req) {
        String account = req.getUsername() == null ? "" : req.getUsername().trim();
        // 登录账号同时兼容「用户名」与「邮箱」：两者共用一个输入框，避免用户记错登录方式
        UserAccount user = userMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .and(w -> w.eq(UserAccount::getUsername, account)
                        .or()
                        .eq(UserAccount::getEmail, account))
                .last("LIMIT 1"));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名/邮箱或密码错误");
        }
        if (!"NORMAL".equals(user.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账户已禁用");
        }
        user.setLastLoginAt(LocalDateTime.now(ZoneOffset.UTC));
        userMapper.updateById(user);
        meta.audit(user.getId(), "AUTH_LOGIN", "user", String.valueOf(user.getId()), "登录成功");
        return issue(user);
    }

    public ApiDtos.TokenVO refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtUtil.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "refresh token 无效或已过期");
        }
        if (!jwtUtil.isRefresh(claims)) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "不是 refresh token");
        }
        LoginUser lu = jwtUtil.toLoginUser(claims);
        UserAccount user = lu.getUserId() == null ? null : userMapper.selectById(lu.getUserId());
        if (user == null || !"NORMAL".equals(user.getStatus())) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "用户状态不可用");
        }
        return issue(user);
    }

    public void logout(String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            redis.revoke(JwtUtil.fingerprint(bearerToken), jwtUtil.getAccessTtlMs());
        }
        meta.audit(AuthContext.userId(), "AUTH_LOGOUT", "user",
                String.valueOf(AuthContext.userId()), "注销当前 token");
    }

    public ApiDtos.UserVO me(Long userId) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return toVO(user);
    }

    private ApiDtos.TokenVO issue(UserAccount user) {
        LoginUser lu = new LoginUser(user.getId(), user.getUsername(), user.getRole(), user.getEmail());
        ApiDtos.TokenVO vo = new ApiDtos.TokenVO();
        vo.setAccessToken(jwtUtil.generateAccess(lu));
        vo.setRefreshToken(jwtUtil.generateRefresh(lu));
        vo.setExpiresIn(jwtUtil.getAccessTtlMs() / 1000);
        return vo;
    }

    public static ApiDtos.UserVO toVO(UserAccount u) {
        ApiDtos.UserVO vo = new ApiDtos.UserVO();
        vo.setId(String.valueOf(u.getId()));
        vo.setUsername(u.getUsername());
        vo.setEmail(u.getEmail());
        vo.setPhone(u.getPhone());
        vo.setRole(u.getRole());
        vo.setStatus(u.getStatus());
        vo.setQuotaSubscription(u.getQuotaSubscription());
        vo.setQuotaScanDaily(u.getQuotaScanDaily());
        vo.setLastLoginAt(u.getLastLoginAt());
        return vo;
    }
}
