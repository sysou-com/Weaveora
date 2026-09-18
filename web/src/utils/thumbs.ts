import { fetchAssetBlob, fetchAssetThumb, listAssets } from '@/api/assets'
import { fetchMarketPreview } from '@/api/market'

/**
 * 缩略图内存缓存：同一会话内返回/切换页面不再重复拉资产列表与整图，
 * 解决“从其它页面返回项目列表缩略图加载慢”。TTL 10 分钟。
 */
const BLOB_TTL = 10 * 60 * 1000

interface ListEntry {
  ts: number
  assetId: string
  mime: string
}
interface BlobEntry {
  ts: number
  url: string
}

const ownListCache = new Map<string, ListEntry>()
const blobCache = new Map<string, BlobEntry>()

function isFresh(ts: number): boolean {
  return Date.now() - ts < BLOB_TTL
}

/** 我的项目缩略图（图片类资产最新一条）；无 → null */
export async function ownThumb(
  ws: string,
  pid: string,
): Promise<{ url: string; mime: string } | null> {
  const listKey = `${ws}/${pid}`
  const cached = ownListCache.get(listKey)
  let assetId = cached?.assetId ?? ''
  let mime = cached?.mime ?? 'image/png'
  if (!cached || !isFresh(cached.ts)) {
    try {
      const list = await listAssets(ws, pid)
      const img = list.find((a) => (a.mime ?? '').startsWith('image/'))
      if (!img) {
        ownListCache.set(listKey, { ts: Date.now(), assetId: '', mime: '' })
        return null
      }
      assetId = img.id
      mime = img.mime
      ownListCache.set(listKey, { ts: Date.now(), assetId, mime })
    } catch {
      return null
    }
  }
  if (!assetId) return null
  const hit = blobCache.get(assetId)
  if (hit && isFresh(hit.ts)) return { url: hit.url, mime }
  // §21：项目卡片只要一张小预览 —— 优先缩略图（几十 KB），服务端没有才回落整张原图
  // （以前这里直接拉整张原图：1280×704 的 png ≈ 2MB，项目列表一屏就是几十 MB）
  const blob = (await fetchAssetThumb(ws, assetId)) ?? (await fetchAssetBlob(ws, assetId))
  if (!blob) return null
  const url = URL.createObjectURL(blob)
  blobCache.set(assetId, { ts: Date.now(), url })
  return { url, mime }
}

/** 集市缩略图 */
export async function marketThumb(pid: string): Promise<{ url: string } | null> {
  const key = `m/${pid}`
  const hit = blobCache.get(key)
  if (hit && isFresh(hit.ts)) return { url: hit.url }
  const blob = await fetchMarketPreview(pid)
  if (!blob) return null
  const url = URL.createObjectURL(blob)
  blobCache.set(key, { ts: Date.now(), url })
  return { url }
}

/** 主动失效（上传/删除资产后由调用方触发） */
export function invalidateOwnThumb(ws: string, pid: string): void {
  ownListCache.delete(`${ws}/${pid}`)
}
