package com.velvet.backend.service;

import com.velvet.backend.exception.ContentViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ContentModerationService 单元测试
 *
 * <p>不依赖 Spring 容器 / 数据库，直接构造 service 并调用 @PostConstruct init()
 * 读取 classpath:moderation/forbidden_words.txt（test classpath 继承 main resources）。
 */
class ContentModerationServiceTest {

    private ContentModerationService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ContentModerationService();
        // 手动触发 @PostConstruct，测试中不启动完整 Spring 容器
        service.init();
    }

    @Test
    @DisplayName("正常文本放行")
    void cleanText_passes() {
        assertDoesNotThrow(
                () -> service.moderateText("今天天气真好，我们去爬山吧", "test"));
    }

    @Test
    @DisplayName("词表中的违规词触发异常")
    void forbiddenWord_throws() {
        // "加我微信" 在联系方式绕规词表中
        assertThrows(ContentViolationException.class,
                () -> service.moderateText("加我微信 12345 聊一聊", "test"));
    }

    @Test
    @DisplayName("null 和空字符串直接放行")
    void nullAndEmpty_areOk() {
        assertDoesNotThrow(() -> service.moderateText(null, "test"));
        assertDoesNotThrow(() -> service.moderateText("", "test"));
        assertDoesNotThrow(() -> service.moderateText("   ", "test"));
    }

    @Test
    @DisplayName("空格绕过也被捕获（normalize 去空格）")
    void spaceBypass_isCaught() {
        // "加我 微 信" → normalize 后等同 "加我微信"
        assertThrows(ContentViolationException.class,
                () -> service.moderateText("加我 微 信", "test"));
    }

    @Test
    @DisplayName("全角/混合大小写绕过被捕获")
    void caseMixing_isCaught() {
        // 词表词 "wx号" 全小写；输入 "WX号" 大写变体
        assertThrows(ContentViolationException.class,
                () -> service.moderateText("WX号：abc123", "test"));
    }

    @Test
    @DisplayName("v25 M2: 全角空格绕过被捕获(U+3000)")
    void fullWidthSpaceBypass_isCaught() {
        // 全角空格 \u3000 分隔
        assertThrows(ContentViolationException.class,
                () -> service.moderateText("加\u3000我\u3000微\u3000信", "test"));
    }

    @Test
    @DisplayName("v25 M2: 标点绕过被捕获(加.我.微.信)")
    void punctuationBypass_isCaught() {
        assertThrows(ContentViolationException.class,
                () -> service.moderateText("加.我.微.信", "test"));
        assertThrows(ContentViolationException.class,
                () -> service.moderateText("加-我-微-信", "test"));
        assertThrows(ContentViolationException.class,
                () -> service.moderateText("加/我/微/信", "test"));
    }

    @Test
    @DisplayName("v25 M2: 符号混合绕过被捕获")
    void symbolBypass_isCaught() {
        // 数学符号 + 货币符号组合
        assertThrows(ContentViolationException.class,
                () -> service.moderateText("加 + 我 + 微 + 信", "test"));
    }

    @Test
    @DisplayName("moderateImage v0 永远 true")
    void moderateImage_alwaysTrue() {
        // v0 占位不检查图片
        assert service.moderateImage("https://cdn.velvet.app/photo.jpg");
        assert service.moderateImage(null);
    }
}
