package com.velvet.backend.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public final class AuthRequests {

    public record RegisterRequest(
            @NotBlank
            @Size(min = 3, max = 32)
            @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母数字下划线")
            String username,

            @NotBlank
            @Size(min = 6, max = 64)
            @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d).+$", message = "密码需包含字母和数字")
            String password,

            @NotBlank
            @Size(min = 1, max = 32)
            String nickname,

            /**
             * 出生日期 · 可选字段
             * Apple 5.1.1(v) 合规：不强制要求生日 · 用户可不填
             * 若填了则必须是过去日期且年满 18 周岁（service 层校验）
             */
            @Past(message = "出生日期必须在过去")
            @JsonFormat(pattern = "yyyy-MM-dd")
            LocalDate birthday,

            /** 用户已同意服务条款 · 必须 true */
            @AssertTrue(message = "请同意用户协议和隐私政策")
            Boolean agreedTerms
    ) {}

    public record LoginRequest(
            @NotBlank String account,
            @NotBlank String password
    ) {}

    /** Apple Sign-In 请求 · identityToken = Apple 返回的 RS256 JWT */
    public record AppleLoginRequest(
            @NotBlank String identityToken,
            String nickname
    ) {}

    /**
     * 登录/注册响应。
     * token = accessToken 的别名，保持向后兼容。
     */
    public record AuthResponse(
            String token,
            String accessToken,
            String refreshToken,
            UserDto user
    ) {
        public AuthResponse(String accessToken, String refreshToken, UserDto user) {
            this(accessToken, accessToken, refreshToken, user);
        }
    }

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}

    public record LogoutRequest(
            @NotBlank String refreshToken
    ) {}

    public record UserDto(
            Long id,
            String username,
            String nickname,
            String avatarUrl,
            String bio,
            Integer momentsCount,
            Integer followersCount,
            Integer followingCount,
            String role,              // USER / MERCHANT / ADMIN
            String merchantStatus     // NONE / PENDING / APPROVED / REJECTED
    ) {}

    private AuthRequests() {}
}
