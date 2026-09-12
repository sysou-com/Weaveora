package studio.weaveora.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P12 参考图主体匹配。
 *
 * <p>真实案例（游幻境项目第 1 镜）：方案里绑定的是「秦可卿」「宝玉」，镜文本写的是
 * 「宝玉与可卿并肩而坐」—— 早期用精确包含 → 「秦可卿」判不中，**该角色的参考图被默默丢掉**，
 * 关键帧就完全不参考人物形象了。所以中文名要能容忍「省姓氏/用名」的写法。
 */
class RefSubjectMatchTest {

    @Test
    void exactNameMatches() {
        assertTrue(JobService.subjectMatches("宝玉与可卿并肩而坐", "宝玉"));
        assertTrue(JobService.subjectMatches("次日清晨，纱帐柔光中", ""));
    }

    @Test
    void courtesyNameMatchesWithoutSurname() {
        // 方案绑全名，镜文只写名 → 必须命中（否则图被丢掉）
        assertTrue(JobService.subjectMatches("宝玉与可卿并肩而坐", "秦可卿"));
        assertTrue(JobService.subjectMatches("宝玉与可卿并肩而坐", "贾宝玉"));
    }

    @Test
    void unrelatedSubjectDoesNotMatch() {
        // 该镜没出现的角色不该被绑定（避免多张脸互相带偏）
        assertFalse(JobService.subjectMatches("宝玉与可卿并肩而坐", "警幻"));
        assertFalse(JobService.subjectMatches("天地骤变，荆榛遍地", "秦可卿"));
    }

    @Test
    void shortTwoCharNameNeedsExactPresence() {
        // 2 字名没有可退的片段，只能精确命中
        assertTrue(JobService.subjectMatches("宝玉在此", "宝玉"));
        assertFalse(JobService.subjectMatches("宝二爷在此", "宝玉"));
    }

    @Test
    void nullTextIsSafe() {
        assertTrue(JobService.subjectMatches(null, ""));
        assertFalse(JobService.subjectMatches(null, "秦可卿"));
    }
}
