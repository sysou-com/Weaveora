#!/usr/bin/env node
/**
 * 检查 .vue 的 <template> 里有没有「用了但没 import 的组件」。
 *
 * 为什么需要它（2026-09-16 实测事故）：
 * `ProjectDetailView.vue` 里写了 `<NSelect …>` 但忘了 `import { NSelect } from 'naive-ui'`，
 * Vue 会把它当**未知元素**处理 —— 编译成 `resolveComponent("NSelect")`，
 * 运行时只打一条 warn（`Failed to resolve component: NSelect`）就渲染成空元素，
 * 于是「位置总控 → 作用范围」那个可搜索下拉框在线上**整块消失**，
 * 而 `vue-tsc --noEmit` 与 `vite build` **都不会报错**（两者都不校验未知组件）。
 * → 所以合到 `npm run build` / `npm run build:prod` 前面，让它挡住发版。
 *
 * 用法：node scripts/check_template_components.mjs      # 退出码 1 = 有漏
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(fileURLToPath(new URL('.', import.meta.url)), '..', 'src')

/** 原生 HTML / 内置元素（不当作组件） */
const NATIVE = new Set(
  `template slot component transition transition-group keep-alive teleport suspense fragment
   router-view router-link
   a abbr address area article aside audio b base bdi bdo blockquote body br button canvas caption
   cite code col colgroup data dd del details dfn dialog div dl dt em embed fieldset figcaption figure
   footer form h1 h2 h3 h4 h5 h6 head header hgroup hr html i iframe img input ins kbd label legend li
   link main map mark menu meta meter nav noscript object ol optgroup option output p picture pre progress
   q rp rt ruby s samp script section select slot small source span strong style sub summary sup table
   tbody td template textarea tfoot th thead time title tr track u ul var video wbr`.split(/\s+/),
)

/** .vue 文件里的 <template> 顶层块（取首尾，容忍内部嵌套 <template>） */
function splitSfc(src) {
  const start = src.indexOf('<template>')
  const end = src.lastIndexOf('</template>')
  if (start < 0 || end < 0) return null
  return { template: src.slice(start + '<template>'.length, end), rest: src.slice(0, start) + src.slice(end + '</template>'.length) }
}

/** <script> 里出现过的标识符（import 的、以及本地 const/function 注册的） */
function declaredNames(script) {
  const ids = new Set()
  for (const m of script.matchAll(/import\s+\{([^}]+)\}/g)) {
    for (const one of m[1].split(',')) ids.add(one.split(/\s+as\s+/).pop().trim())
  }
  for (const m of script.matchAll(/import\s+([A-Za-z0-9_$]+)/g)) ids.add(m[1])
  for (const m of script.matchAll(/(?:const|let|var|function)\s+([A-Za-z0-9_$]+)/g)) ids.add(m[1])
  return ids
}

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p, out)
    else if (name.endsWith('.vue')) out.push(p)
  }
  return out
}

let bad = 0
for (const file of walk(ROOT)) {
  const parts = splitSfc(readFileSync(file, 'utf8'))
  if (!parts) continue
  const declared = declaredNames(parts.rest)
  const used = new Set()
  for (const m of parts.template.matchAll(/<([A-Za-z][A-Za-z0-9-]*)/g)) used.add(m[1])

  const missing = [...used].filter((tag) => {
    if (tag.includes('-')) return false          // kebab 一律当原生/全局（本项目没有 n-* 写法）
    if (!/^[A-Z]/.test(tag)) return false        // PascalCase 才可能是组件
    if (NATIVE.has(tag.toLowerCase())) return false
    return !declared.has(tag)
  })
  if (missing.length) {
    bad++
    console.error(`${relative(process.cwd(), file).split(sep).join('/')} → 模板用了但没 import：${missing.join('、')}`)
  }
}

if (bad) {
  console.error(`\n✗ ${bad} 个文件有未导入组件：Vue 只会打一条 warn，页面上该控件会静默消失。补 import 后重跑。`)
  process.exit(1)
}
console.log('✓ 模板组件导入检查通过')
