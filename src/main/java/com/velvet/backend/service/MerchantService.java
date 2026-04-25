package com.velvet.backend.service;

import com.velvet.backend.entity.Merchant;
import com.velvet.backend.entity.User;
import com.velvet.backend.exception.AppException;
import com.velvet.backend.repository.MerchantRepository;
import com.velvet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家认证服务
 *
 * <p>流程: 用户填资料 → status=PENDING → 后台审核 → APPROVED/REJECTED
 * <p>APPROVED 后 user.role 升级为 MERCHANT，可发布商品
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantRepository merchantRepo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;

    // ==========================================================================
    // DTO
    // ==========================================================================
    public record MerchantDto(
            Long id,
            Long userId,
            String shopName,
            String shopAvatar,
            String shopCover,
            String shopIntro,
            String contactName,
            String contactPhone,
            String contactWechat,
            String sellerType,
            String personalRealName,
            String personalIdNo,
            String receiveAlipay,
            String receiveWechat,
            String receiveBankCard,
            String receiveBankName,
            String status,
            String reviewNote,
            BigDecimal rating,
            Integer salesCount,
            LocalDateTime createdAt
    ) {}

    public record ApplyRequest(
            String shopName,
            String shopAvatar,
            String shopCover,
            String shopIntro,
            String contactName,
            String contactPhone,
            String contactWechat,
            String idCardFront,
            String idCardBack,
            String businessLicense,
            String businessNo,
            // —— 个人卖家字段 —— //
            String sellerType,           // PERSONAL / BUSINESS
            String personalRealName,
            String personalIdNo,
            String receiveAlipay,
            String receiveWechat,
            String receiveBankCard,
            String receiveBankName
    ) {}

    public record ReviewRequest(
            Boolean approve,
            String note
    ) {}

    // ==========================================================================
    // 操作
    // ==========================================================================

    /**
     * 申请商家认证
     */
    @Transactional
    public MerchantDto apply(Long userId, ApplyRequest req) {
        if (req.shopName() == null || req.shopName().isBlank()) {
            throw new AppException("INVALID_REQUEST", "店铺名称必填");
        }
        String sellerType = req.sellerType() != null ? req.sellerType() : "PERSONAL";
        if ("PERSONAL".equals(sellerType)) {
            // 个人卖家：真实姓名 + 手机号 + 至少一个收款账号
            if (isBlank(req.personalRealName())) {
                throw new AppException("INVALID_REQUEST", "真实姓名必填");
            }
            if (isBlank(req.contactPhone())) {
                throw new AppException("INVALID_REQUEST", "联系电话必填");
            }
            if (isBlank(req.receiveWechat()) && isBlank(req.receiveAlipay())
                    && isBlank(req.receiveBankCard())) {
                throw new AppException("INVALID_REQUEST",
                        "请至少填写一个收款账号（微信 / 支付宝 / 银行卡）");
            }
        } else {
            // 企业商家：联系人 + 电话 + 营业执照号
            if (isBlank(req.contactName()) || isBlank(req.contactPhone())) {
                throw new AppException("INVALID_REQUEST", "联系人姓名 + 电话必填");
            }
            if (isBlank(req.businessNo())) {
                throw new AppException("INVALID_REQUEST", "企业商家需填写统一社会信用代码");
            }
        }
        // 已申请或已认证 → 不能重复申请
        var existing = merchantRepo.findByUserId(userId);
        if (existing.isPresent()) {
            String s = existing.get().getStatus();
            if ("APPROVED".equals(s)) {
                throw new AppException("ALREADY_APPROVED", "已是认证商家");
            }
            if ("PENDING".equals(s)) {
                throw new AppException("PENDING_REVIEW", "认证审核中，请等待");
            }
            // REJECTED → 重新提交
            Merchant m = existing.get();
            applyRequestToMerchant(req, m);
            m.setStatus("PENDING");
            m.setReviewNote(null);
            m = merchantRepo.save(m);
            updateUserStatus(userId, "PENDING");
            return toDto(m);
        }
        Merchant m = applyRequestToMerchant(req, Merchant.builder().userId(userId).build());
        m.setStatus("PENDING");
        m = merchantRepo.save(m);
        updateUserStatus(userId, "PENDING");
        return toDto(m);
    }

    /**
     * 后台审核（管理员用）
     */
    @Transactional
    public MerchantDto review(Long adminUserId, Long merchantId, ReviewRequest req) {
        // TODO: 检查 admin 权限
        Merchant m = merchantRepo.findById(merchantId)
                .orElseThrow(() -> new AppException("NOT_FOUND", "商家不存在"));
        if (!"PENDING".equals(m.getStatus())) {
            throw new AppException("INVALID_STATE", "只能审核 PENDING 状态");
        }
        boolean approve = req.approve() != null && req.approve();
        m.setStatus(approve ? "APPROVED" : "REJECTED");
        m.setReviewNote(req.note());
        m.setReviewedBy(adminUserId);
        m.setReviewedAt(LocalDateTime.now());
        m = merchantRepo.save(m);

        // 升级 user.role
        User u = userRepo.findById(m.getUserId())
                .orElseThrow(() -> new AppException("NOT_FOUND", "用户不存在"));
        u.setMerchantStatus(approve ? "APPROVED" : "REJECTED");
        if (approve) {
            u.setRole("MERCHANT");
        }
        userRepo.save(u);

        // 通知用户
        try {
            notificationService.create(
                    m.getUserId(),
                    "SYSTEM",
                    approve ? "商家认证已通过" : "商家认证未通过",
                    req.note() != null ? req.note() : "",
                    null,
                    "MERCHANT",
                    m.getId());
        } catch (Exception e) {
            log.warn("[notify] failed merchantId={}", m.getId(), e);
        }

        return toDto(m);
    }

    /**
     * 我的商家资料（自查）
     */
    @Transactional(readOnly = true)
    public MerchantDto myMerchant(Long userId) {
        return merchantRepo.findByUserId(userId).map(this::toDto).orElse(null);
    }

    /**
     * 公开商家资料
     */
    @Transactional(readOnly = true)
    public MerchantDto getPublic(Long userId) {
        return merchantRepo.findByUserId(userId)
                .filter(m -> "APPROVED".equals(m.getStatus()))
                .map(this::toDto)
                .orElse(null);
    }

    /**
     * 检查用户是否有发布商品权限
     * @return true if APPROVED merchant
     */
    public boolean canPublishItem(Long userId) {
        return merchantRepo.findByUserId(userId)
                .map(m -> "APPROVED".equals(m.getStatus()))
                .orElse(false);
    }

    // ==========================================================================
    // helpers
    // ==========================================================================
    private Merchant applyRequestToMerchant(ApplyRequest req, Merchant m) {
        m.setShopName(req.shopName());
        m.setShopAvatar(req.shopAvatar());
        m.setShopCover(req.shopCover());
        m.setShopIntro(req.shopIntro());
        // 个人卖家没填 contactName 就用 personalRealName 兜底（DB 非空约束）
        m.setContactName(firstNonBlank(req.contactName(), req.personalRealName(), "卖家"));
        m.setContactPhone(firstNonBlank(req.contactPhone(), ""));
        m.setContactWechat(req.contactWechat());
        m.setIdCardFront(req.idCardFront());
        m.setIdCardBack(req.idCardBack());
        m.setBusinessLicense(req.businessLicense());
        m.setBusinessNo(req.businessNo());
        // 个人卖家字段
        m.setSellerType(req.sellerType() != null ? req.sellerType() : "PERSONAL");
        m.setPersonalRealName(req.personalRealName());
        m.setPersonalIdNo(req.personalIdNo());
        m.setReceiveAlipay(req.receiveAlipay());
        m.setReceiveWechat(req.receiveWechat());
        m.setReceiveBankCard(req.receiveBankCard());
        m.setReceiveBankName(req.receiveBankName());
        return m;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String firstNonBlank(String... xs) {
        for (String x : xs) if (x != null && !x.isBlank()) return x;
        return null;
    }

    private void updateUserStatus(Long userId, String mStatus) {
        userRepo.findById(userId).ifPresent(u -> {
            u.setMerchantStatus(mStatus);
            userRepo.save(u);
        });
    }

    private MerchantDto toDto(Merchant m) {
        return new MerchantDto(
                m.getId(),
                m.getUserId(),
                m.getShopName(),
                m.getShopAvatar(),
                m.getShopCover(),
                m.getShopIntro(),
                m.getContactName(),
                m.getContactPhone(),
                m.getContactWechat(),
                m.getSellerType(),
                m.getPersonalRealName(),
                // ID 号脱敏：只展示 *** + 后 4 位
                maskId(m.getPersonalIdNo()),
                m.getReceiveAlipay(),
                m.getReceiveWechat(),
                maskCard(m.getReceiveBankCard()),
                m.getReceiveBankName(),
                m.getStatus(),
                m.getReviewNote(),
                m.getRating(),
                m.getSalesCount(),
                m.getCreatedAt()
        );
    }

    private static String maskId(String id) {
        if (id == null || id.length() < 8) return id;
        return id.substring(0, 3) + "****" + id.substring(id.length() - 4);
    }

    private static String maskCard(String c) {
        if (c == null || c.length() < 8) return c;
        return "****" + c.substring(c.length() - 4);
    }

    // ==========================================================================
    // Admin 入口
    // ==========================================================================

    @Transactional(readOnly = true)
    public java.util.List<MerchantDto> listPending() {
        return merchantRepo.findByStatusOrderByCreatedAtDesc("PENDING").stream()
                .map(this::toDto)
                .toList();
    }
}
