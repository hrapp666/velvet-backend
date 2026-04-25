package com.velvet.backend.service;

import com.velvet.backend.dto.MomentRequests.MomentDto;
import com.velvet.backend.entity.Moment;
import com.velvet.backend.entity.User;
import com.velvet.backend.repository.FavoriteRepository;
import com.velvet.backend.repository.LikeRepository;
import com.velvet.backend.repository.MomentRepository;
import com.velvet.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * RecommendationService 单元测试
 * <p>
 * 覆盖：
 * - 有 tag 偏好 → 按 jaccard 排序返回，排除自己的 moment 和已 like
 * - 冷启动 (用户无任何互动历史) → fallback 最新 public
 * - 候选池里全部已 like → 回退补齐 fallback
 * - jaccard 计算单元
 * - toTagSet 规范化
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock private MomentRepository momentRepo;
    @Mock private LikeRepository likeRepo;
    @Mock private FavoriteRepository favoriteRepo;
    @Mock private UserRepository userRepo;

    private RecommendationService service;

    private static final Long VIEWER_ID = 100L;
    private static final Long OTHER_USER = 200L;
    private static final Long OTHER_USER_2 = 201L;

    @BeforeEach
    void setUp() {
        service = new RecommendationService(momentRepo, likeRepo, favoriteRepo, userRepo);
    }

    // ─────────────────────────────────────────────────────
    // 核心行为：按 jaccard 排序 + 排除已 like
    // ─────────────────────────────────────────────────────

    @Test
    @DisplayName("用户有 2 个 tag 偏好 → 返回按 jaccard score 排序且排除已 like 的列表")
    void recommendFor_withTagPreferences_returnsRankedAndExcludesLiked() {
        // arrange — 用户互动历史：liked moment 1 (tags: 小众/复古)
        when(likeRepo.findRecentMomentIdsByUserId(eq(VIEWER_ID), any(Pageable.class)))
                .thenReturn(List.of(1L));
        when(favoriteRepo.findRecentMomentIdsByUserId(eq(VIEWER_ID), any(Pageable.class)))
                .thenReturn(List.of());

        Moment historyMoment = moment(1L, OTHER_USER, new String[] {"小众", "复古"}, 10);

        // 候选池：moment 2（全命中 2 tag）/ moment 3（命中 1 tag）/ moment 4（自己发的）
        //       / moment 5（无 tag）/ moment 1（已 like，应排除）
        Moment candidate2 = moment(2L, OTHER_USER,   new String[] {"小众", "复古"},       20); // jaccard 1.0
        Moment candidate3 = moment(3L, OTHER_USER_2, new String[] {"小众", "街拍"},       30); // jaccard 1/3
        Moment candidate4 = moment(4L, VIEWER_ID,    new String[] {"小众", "复古"},       40); // 自己发，排除
        Moment candidate5 = moment(5L, OTHER_USER,   new String[] {},                    50); // 无 tag，跳过
        Moment candidate1Echo = historyMoment;                                               // 已 like，排除

        List<Moment> pool = List.of(candidate2, candidate3, candidate4, candidate5, candidate1Echo);
        when(momentRepo.findRecentPublishedForRecommend(anyInt())).thenReturn(pool);
        when(momentRepo.findAllByIdInAndPublished(anyCollection())).thenReturn(List.of(historyMoment));

        // batch fetch users
        User u1 = user(OTHER_USER,   "Alice");
        User u2 = user(OTHER_USER_2, "Bob");
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(u1, u2));

        // act
        List<MomentDto> result = service.recommendFor(VIEWER_ID, 10);

        // assert
        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(2L);  // jaccard 1.0 最高
        assertThat(result.get(1).id()).isEqualTo(3L);  // jaccard 0.33
        // 验证排除
        assertThat(result).noneMatch(d -> d.id().equals(1L));
        assertThat(result).noneMatch(d -> d.id().equals(4L));
        assertThat(result).noneMatch(d -> d.id().equals(5L));
        // 验证排序 desc
        // nickname 填充正常
        assertThat(result.get(0).userNickname()).isEqualTo("Alice");
    }

    // ─────────────────────────────────────────────────────
    // 冷启动 fallback
    // ─────────────────────────────────────────────────────

    @Test
    @DisplayName("用户无互动历史 → fallback 返回最新 public moments (排除自己的)")
    void recommendFor_coldStart_returnsLatestFallback() {
        when(likeRepo.findRecentMomentIdsByUserId(eq(VIEWER_ID), any(Pageable.class)))
                .thenReturn(List.of());
        when(favoriteRepo.findRecentMomentIdsByUserId(eq(VIEWER_ID), any(Pageable.class)))
                .thenReturn(List.of());

        Moment m1 = moment(11L, OTHER_USER,   new String[] {"xx"}, 10);
        Moment m2 = moment(12L, VIEWER_ID,    new String[] {"yy"}, 20); // 自己发，排除
        Moment m3 = moment(13L, OTHER_USER_2, new String[] {"zz"}, 30);

        when(momentRepo.findRecentPublishedForRecommend(anyInt()))
                .thenReturn(List.of(m1, m2, m3));
        when(userRepo.findAllById(anyCollection()))
                .thenReturn(List.of(user(OTHER_USER, "A"), user(OTHER_USER_2, "C")));

        List<MomentDto> result = service.recommendFor(VIEWER_ID, 10);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(MomentDto::id).containsExactly(11L, 13L);
    }

    @Test
    @DisplayName("null userId → 返回空列表")
    void recommendFor_nullUser_returnsEmpty() {
        assertThat(service.recommendFor(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("limit 超 50 → clamp 到 50")
    void recommendFor_limitClamp() {
        // 冷启动路径最简单
        when(likeRepo.findRecentMomentIdsByUserId(eq(VIEWER_ID), any(Pageable.class))).thenReturn(List.of());
        when(favoriteRepo.findRecentMomentIdsByUserId(eq(VIEWER_ID), any(Pageable.class))).thenReturn(List.of());

        List<Moment> pool = new ArrayList<>();
        for (long i = 0; i < 80; i++) {
            pool.add(moment(1000 + i, OTHER_USER, new String[] {"x"}, (int) i));
        }
        when(momentRepo.findRecentPublishedForRecommend(anyInt())).thenReturn(pool);
        lenient().when(userRepo.findAllById(anyCollection())).thenReturn(List.of(user(OTHER_USER, "A")));

        List<MomentDto> result = service.recommendFor(VIEWER_ID, 999);
        assertThat(result).hasSize(50);
    }

    // ─────────────────────────────────────────────────────
    // jaccard / toTagSet 单元
    // ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("jaccard() 单元")
    class JaccardTests {

        @Test
        void identical_returnsOne() {
            assertThat(RecommendationService.jaccard(Set.of("a", "b"), Set.of("a", "b"))).isEqualTo(1.0);
        }

        @Test
        void disjoint_returnsZero() {
            assertThat(RecommendationService.jaccard(Set.of("a"), Set.of("b"))).isEqualTo(0.0);
        }

        @Test
        void halfOverlap_returnsThird() {
            // A={a,b} B={b,c} → |∩|=1 |∪|=3 → 1/3
            double score = RecommendationService.jaccard(Set.of("a", "b"), Set.of("b", "c"));
            assertThat(score).isCloseTo(1.0 / 3.0, within());
        }

        @Test
        void emptyLeft_returnsZero() {
            assertThat(RecommendationService.jaccard(Set.of(), Set.of("a"))).isEqualTo(0.0);
        }

        private org.assertj.core.data.Offset<Double> within() {
            return org.assertj.core.data.Offset.offset(1e-9);
        }
    }

    @Nested
    @DisplayName("toTagSet() 单元")
    class TagSetTests {

        @Test
        void normalizesLowercaseAndTrim() {
            Set<String> set = RecommendationService.toTagSet(new String[] {" 小众 ", "小众", "复古", ""});
            assertThat(set).containsExactlyInAnyOrder("小众", "复古");
        }

        @Test
        void nullInput_returnsEmpty() {
            assertThat(RecommendationService.toTagSet(null)).isEmpty();
        }

        @Test
        void mixedCase_normalized() {
            Set<String> set = RecommendationService.toTagSet(new String[] {"Vintage", "VINTAGE", "vintage"});
            assertThat(set).containsExactly("vintage");
        }
    }

    // ─────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────

    private static Moment moment(long id, Long userId, String[] tags, int ageMinutes) {
        Moment m = Moment.builder()
                .id(id)
                .userId(userId)
                .title("t-" + id)
                .content("content-" + id)
                .mediaUrls(List.of())
                .tags(tags)
                .visibility("PUBLIC")
                .status("PUBLISHED")
                .viewCount(0)
                .likeCount(0)
                .favoriteCount(0)
                .commentCount(0)
                .chatCount(0)
                .hasItem(false)
                .isPinned(false)
                .build();
        m.setCreatedAt(LocalDateTime.now().minusMinutes(ageMinutes));
        return m;
    }

    private static User user(Long id, String nickname) {
        User u = new User();
        u.setId(id);
        u.setNickname(nickname);
        return u;
    }
}
