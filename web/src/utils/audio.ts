import type { SelectOption } from 'naive-ui'

/** 自托管 CosyVoice 常用内置音色（其余可手输：GPU 机器上的参考音频路径做 zero-shot 克隆） */
export const VOICE_PRESETS = ['中文女', '中文男', '英文女', '英文男', '日语女', '韩语女', '粤语女']

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
