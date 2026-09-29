package studio.weaveora.director;

import org.junit.jupiter.api.Test;
import studio.weaveora.shared.api.BizException;
import studio.weaveora.shared.api.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P16（2026-09-29）：LLM 失败分类单测。
 *
 * <p>背景：供应商 402 Insufficient Balance 被统一报成「返回不可解析」→ 用户反复精简需求重试。
 */
class DirectorLlmFailureClassifyTest {

    @Test
    void balanceExhaustedReportedAsBalanceNotParse() {
        BizException e = DirectorService.classifyLlmFailure(new IllegalStateException(
                "director LLM 调用失败（2 次尝试）"));
        assertEquals(ErrorCode.DIRECTOR_PARSE_FAILED, e.code());
        // 真正的余额不足（供应商原文）
        BizException b = DirectorService.classifyLlmFailure(new IllegalStateException(
                "402 Payment Required: {\"error\":{\"message\":\"Insufficient Balance (request_id: x)\"}}"));
        assertEquals(ErrorCode.DIRECTOR_LLM_BALANCE, b.code());
        assertTrue(b.getMessage().contains("余额不足"));
        assertTrue(b.getMessage().contains("不必精简"), "要明确告诉用户不是需求的问题");
    }

    @Test
    void truncatedReportedAsLengthProblem() {
        BizException e = DirectorService.classifyLlmFailure(new IllegalStateException(
                "LLM 输出达到 max_tokens 被截断（finish_reason=length）"));
        assertEquals(ErrorCode.DIRECTOR_UNAVAILABLE, e.code());
        assertTrue(e.getMessage().contains("截断"));
    }

    @Test
    void networkFailureReportedAsUnavailable() {
        BizException e = DirectorService.classifyLlmFailure(new IllegalStateException(
                "java.net.http.HttpConnectTimeoutException: HTTP connect timed out"));
        assertEquals(ErrorCode.DIRECTOR_UNAVAILABLE, e.code());
        assertTrue(e.getMessage().contains("连接失败"));
    }

    @Test
    void unparseableStaysParseFailed() {
        BizException e = DirectorService.classifyLlmFailure(new IllegalStateException("Unexpected character 'x'"));
        assertEquals(ErrorCode.DIRECTOR_PARSE_FAILED, e.code());
    }
}
