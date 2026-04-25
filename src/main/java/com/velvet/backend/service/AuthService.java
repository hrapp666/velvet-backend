package com.velvet.backend.service;

import com.velvet.backend.dto.AuthRequests.*;
import com.velvet.backend.entity.User;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.UserRepository;
import com.velvet.backend.security.AppleIdTokenVerifier;
import com.velvet.backend.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    /** App Store 约会类 app 最低年龄要求 */
    private static final int MIN_AGE = 18;

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;
    private final AppleIdTokenVerifier appleIdTokenVerifier;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepo.existsByUsername(req.username())) {
            throw new AppException("USERNAME_TAKEN", "用户名已被使用");
        }

        // 18+ 校验 · App Store 合规
        LocalDate today = LocalDate.now();
        int age = Period.between(req.birthday(), today).getYears();
        if (age < MIN_AGE) {
            throw new AppException("UNDER_AGE", "抱歉 · 需年满 18 周岁");
        }

        User user = User.builder()
                .username(req.username())
                .nickname(req.nickname())
                .passwordHash(encoder.encode(req.password()))
                .birthday(req.birthday())
                .role("USER")
                .status("ACTIVE")
                .build();
        User saved = userRepo.save(user);

        String accessToken = jwtUtils.generateToken(saved.getId(), saved.getUsername(), saved.getRole());
        String refreshToken = refreshTokenService.createRefreshToken(saved.getId(), saved.getUsername());
        return new AuthResponse(accessToken, refreshToken, toDto(saved));
    }

    @Transactional(readOnly = true)
    public UserDto getMe(Long userId) {
        User u = userRepo.findById(userId)
                .orElseThrow(() -> new AppException("NOT_FOUND", "用户不存在"));
        return toDto(u);
    }

    public AuthResponse login(LoginRequest req) {
        // 支持用户名 / 邮箱 / 手机号 三种登录
        User user = userRepo.findByUsername(req.account())
                .or(() -> userRepo.findByEmail(req.account()))
                .or(() -> userRepo.findByPhone(req.account()))
                .orElseThrow(() -> new AppException("INVALID_CREDENTIALS", "账号或密码错误"));

        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            throw new AppException("INVALID_CREDENTIALS", "账号或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new AppException("ACCOUNT_DISABLED", "账号已停用");
        }

        String accessToken = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = refreshTokenService.createRefreshToken(user.getId(), user.getUsername());
        return new AuthResponse(accessToken, refreshToken, toDto(user));
    }

    /**
     * Apple Sign-In · 验证 identityToken (RS256 via JWKS) 后 find-or-create 用户。
     * 仅首次登录时 Apple 会返回 fullName · 之后只能拿到 sub。
     */
    @Transactional
    public AuthResponse appleLogin(AppleLoginRequest req) {
        String appleSub = appleIdTokenVerifier.verifyAndExtractSub(req.identityToken());

        User user = userRepo.findByAppleUserId(appleSub).orElseGet(() -> {
            String username = generateAppleUsername(appleSub);
            String nickname = (req.nickname() != null && !req.nickname().isBlank())
                    ? req.nickname().trim()
                    : "私藏 · " + username.substring(username.length() - 4);
            // Apple 用户没有可登录密码 · 用随机 UUID 哈希占位 · 后续如需密码登录走"找回密码"流程
            String placeholderPassword = encoder.encode(UUID.randomUUID().toString());
            User newUser = User.builder()
                    .username(username)
                    .nickname(nickname)
                    .passwordHash(placeholderPassword)
                    .appleUserId(appleSub)
                    .role("USER")
                    .status("ACTIVE")
                    .build();
            return userRepo.save(newUser);
        });

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new AppException("ACCOUNT_DISABLED", "账号已停用");
        }

        String accessToken = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = refreshTokenService.createRefreshToken(user.getId(), user.getUsername());
        return new AuthResponse(accessToken, refreshToken, toDto(user));
    }

    /** 给 Apple 用户分配一个不冲突的 username · 形如 apl_xxxxxxxx · 最多重试 5 次。 */
    private String generateAppleUsername(String appleSub) {
        // 用 sub 后 8 位 hash 做 stable seed · 不直接暴露 sub
        String seed = Integer.toHexString(appleSub.hashCode() & 0x7fffffff);
        for (int i = 0; i < 5; i++) {
            String candidate = "apl_" + seed + (i == 0 ? "" : "_" + i);
            if (!userRepo.existsByUsername(candidate)) {
                return candidate;
            }
        }
        // 极端兜底 · 用 UUID 短码
        return "apl_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 用 refresh token 换取新 access token。
     * refresh token 本身不轮换（简化实现，7 天后自然过期）。
     */
    public AuthResponse refresh(String refreshToken) {
        Long userId = refreshTokenService.validate(refreshToken);
        if (userId == null) {
            throw new AppException("INVALID_REFRESH_TOKEN", "refresh token 无效或已过期");
        }

        String username = refreshTokenService.parseUsername(refreshToken);
        if (username == null) {
            throw new AppException("INVALID_REFRESH_TOKEN", "refresh token 无效");
        }

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new AppException("NOT_FOUND", "用户不存在"));

        if (!"ACTIVE".equals(user.getStatus())) {
            // 账号已停用，同时黑名单 refresh token
            refreshTokenService.blacklist(refreshToken);
            throw new AppException("ACCOUNT_DISABLED", "账号已停用");
        }

        String newAccessToken = jwtUtils.generateToken(userId, username, user.getRole());
        return new AuthResponse(newAccessToken, refreshToken, toDto(user));
    }

    /**
     * 登出：黑名单 refresh token。
     */
    public void logout(String refreshToken) {
        refreshTokenService.blacklist(refreshToken);
    }

    private UserDto toDto(User u) {
        return new UserDto(
                u.getId(),
                u.getUsername(),
                u.getNickname(),
                u.getAvatarUrl(),
                u.getBio(),
                u.getMomentsCount(),
                u.getFollowersCount(),
                u.getFollowingCount(),
                u.getRole(),
                u.getMerchantStatus() != null ? u.getMerchantStatus() : "NONE"
        );
    }
}
