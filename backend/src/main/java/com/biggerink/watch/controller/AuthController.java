package com.biggerink.watch.controller;

import com.biggerink.watch.common.ApiDtos;
import com.biggerink.watch.common.R;
import com.biggerink.watch.security.AuthContext;
import com.biggerink.watch.service.AuthService;
import com.biggerink.watch.service.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final IdempotencyService idempotency;

    public AuthController(AuthService authService, IdempotencyService idempotency) {
        this.authService = authService;
        this.idempotency = idempotency;
    }

    @PostMapping("/register")
    public R<ApiDtos.UserVO> register(
            @Valid @RequestBody ApiDtos.RegisterReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        idempotency.require(idemKey, "anon:" + req.getUsername());
        return R.ok(authService.register(req));
    }

    @PostMapping("/login")
    public R<ApiDtos.TokenVO> login(@Valid @RequestBody ApiDtos.LoginReq req) {
        return R.ok(authService.login(req));
    }

    @PostMapping("/refresh")
    public R<ApiDtos.TokenVO> refresh(@Valid @RequestBody ApiDtos.RefreshReq req) {
        return R.ok(authService.refresh(req.getRefreshToken()));
    }

    @PostMapping("/logout")
    public R<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        authService.logout(token);
        return R.ok();
    }

    @GetMapping("/me")
    public R<ApiDtos.UserVO> me() {
        return R.ok(authService.me(AuthContext.userId()));
    }
}
