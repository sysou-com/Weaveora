import type { ProjectMode } from '@/api/types'

export const MODE_LABEL: Record<ProjectMode, string> = {
  image: '图片',
  video: '视频',
  mixed: '混合',
}

export function modeLabel(mode: ProjectMode | string): string {
  return MODE_LABEL[mode as ProjectMode] ?? mode
}

export const ASPECT_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '1:1', label: '1:1 方形' },
  { value: '3:2', label: '3:2 横图' },
  { value: '2:3', label: '2:3 竖图' },
  { value: '16:9', label: '16:9 宽屏' },
  { value: '9:16', label: '9:16 竖屏' },
]

export const VIDEO_DURATIONS: Array<{ value: number; label: string }> = [
  { value: 6, label: '6 秒' },
  { value: 10, label: '10 秒' },
  { value: 12, label: '12 秒' },
  { value: 15, label: '15 秒' },
  { value: 30, label: '30 秒' },
]

export const DEFAULT_VIDEO_DURATION = 12

/** ISO OffsetDateTime（UTC）→ 本地「YYYY-MM-DD HH:mm」 */
export function formatDate(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatDateShort(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function aspectNote(aspect: string): string {
  switch (aspect) {
    case '16:9':
      return '宽屏'
    case '9:16':
      return '竖屏'
    case '1:1':
      return '方形'
    case '3:2':
      return '横画幅'
    case '2:3':
      return '竖画幅'
    default:
      return aspect
  }
}
