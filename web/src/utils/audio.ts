import type { SelectOption } from 'naive-ui'

/**
 * 自托管配音的内置音色。
 * 这 7 个 = CosyVoice-300M-SFT 的 spk2info（GPU 机器上 `/data/audio/CosyVoice/pretrained_models/CosyVoice-300M-SFT/spk2info.pt`）。
 * 注意模型里是「日语男」而非「日语女」。
 * 其余可手输：GPU 机器上的参考音频（wav）绝对路径 → 走 CosyVoice2-0.5B 的 zero-shot 克隆。
 */
export const VOICE_PRESETS = ['中文女', '中文男', '英文女', '英文男', '日语男', '韩语女', '粤语女']

/** 音乐生成常用情绪（写入 plan.audio.music_mood，作为配乐 prompt 的 mood 部分） */
export const MUSIC_MOOD_PRESETS = [
  '史诗磅礴',
  '温暖治愈',
  '紧张悬疑',
  '空灵神秘',
  '浪漫柔情',
  '轻快活泼',
  '古典中国风',
  '电子科技感',
  '悲伤低沉',
]

export const voiceOptions: SelectOption[] = VOICE_PRESETS.map((v) => ({ label: v, value: v }))
export const moodOptions: SelectOption[] = MUSIC_MOOD_PRESETS.map((v) => ({ label: v, value: v }))
