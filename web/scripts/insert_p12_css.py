#!/usr/bin/env python3
"""P12：往 ProjectDetailView.vue 的 </style> 前插入类型 Tab 与移动端适配样式（幂等）。"""
import sys

P = "src/views/ProjectDetailView.vue"
MARK = "P12：类型 Tab + 移动端适配"

s = open(P, encoding="utf-8").read()
if MARK in s:
    print("already inserted, skip")
    sys.exit(0)

assert s.rstrip().endswith("</style>"), "文件末尾不是 </style>"

CSS = """
/* ---------------- P12：类型 Tab + 移动端适配 ---------------- */
.type-tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 2px 0 6px;
}
.type-tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid var(--wv-line);
  background: transparent;
  color: inherit;
  font-size: 12px;
  cursor: pointer;
  opacity: 0.75;
}
.type-tab:hover { opacity: 1; }
.type-tab.on {
  opacity: 1;
  border-color: color-mix(in srgb, var(--wv-accent) 60%, var(--wv-line));
  background: color-mix(in srgb, var(--wv-accent) 16%, transparent);
}
.type-tab-n { font-size: 10px; opacity: 0.7; }
.jobs-head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
/* 窄屏（手机）：卡片/操作区堆叠，Tab 收紧，缩略图换行 */
@media (max-width: 720px) {
  .jobs-head,
  .jobs-head-actions {
    flex-wrap: wrap;
    gap: 8px;
  }
  .jobs-actions {
    flex-wrap: wrap;
    gap: 6px;
  }
  .type-tab {
    padding: 3px 8px;
    font-size: 11.5px;
  }
  .gallery-grid { flex-wrap: wrap; }
}
"""

s = s.rstrip()
s = s[: s.rfind("</style>")] + CSS.lstrip("\n") + "</style>\n"
open(P, "w", encoding="utf-8", newline="\n").write(s)
print("css inserted")
