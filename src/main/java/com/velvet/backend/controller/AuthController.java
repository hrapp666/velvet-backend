package com.velvet.backend.controller;

import com.velvet.backend.dto.AuthRequests.*;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    /** Apple Sign-In · iOS 上架强制 · 后端走 Apple JWKS 校验 RS256 identityToken */
    @PostMapping("/apple")
    public AuthResponse appleLogin(@Valid @RequestBody AppleLoginRequest req) {
        return authService.appleLogin(req);
    }

    /** 用 refresh token 换取新 access token */
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    /** 登出：黑名单 refresh token */
    @PostMapping("/logout")
    public java.util.Map<String, String> logout(@Valid @RequestBody LogoutRequest req) {
        authService.logout(req.refreshToken());
        return java.util.Map.of("message", "已登出");
    }

    /** 当前登录用户的最新资料（含 role / merchantStatus） */
    @GetMapping("/me")
    public UserDto me() {
        return authService.getMe(currentUserId());
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new AppException("UNAUTHORIZED", "请先登录");
        }
        return id;
    }
}
