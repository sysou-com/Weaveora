# weaveora-web — 织影 Web 创作台（Vue 3.5 + Vite 6 + TS + Naive UI）

W1 范围：登录 / 注册、项目列表、新建项目，对接 `http://localhost:8080` 的
`/api/v1/auth`、`/api/v1/me`、`/api/v1/projects`（`X-Workspace-Id` 隔离头）。

## 本地起

```bash
# 0) 先起后端（另一终端，见 api/README）：
#    export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
#    cd ../api && mvn spring-boot:run        # :8080，profile dev 连本机 weaveora_dev + Redis db1
#    （Redis 需可用：ssh -N -L 6379:127.0.0.1:6379 root@sysou.com，或用本机临时实例）

# 1) 依赖（Node ≥ 22）
npm install

# 2) 开发
npm run dev            # http://localhost:5173，/api 反代到 :8080

# 3) 产物 / 检查
npm run build          # → dist/
npm run type-check     # vue-tsc --noEmit
npm run preview
```

## 环境变量

见 `.env.example`。默认同源（Vite 反代 / 生产 nginx 反代 `/api`）；直连可设 `VITE_API_BASE_URL`。

## 目录（§18.1 子集）

```
src/
  api/        client.ts（fetch + 401 续期重试）+ auth.ts / projects.ts / types.ts / session.ts(token 存取)
  stores/     auth.ts（Pinia：token / /me / 工作区选择）
  router/     守卫：无 token → /login；受保护路由静默补 /me
  layouts/    AppLayout.vue（顶栏 + 用户菜单）
  views/      LoginView / ProjectListView / ProjectNewView / ProjectDetailView(W2 占位)
  styles/     tokens.css（§1.3 放映厅色板）+ base.css
  theme/      naive-ui 暗色覆写（§1.3 token）
  utils/      format.ts（模式/画幅/时长/日期）
```

## 契约注意

- 项目端点必须带 `X-Workspace-Id`（取 `/me` 的 workspaces[0]，后续可切）。
- 401 → 自动 refresh 一次重试；失败清会话跳登录。
- 错误统一为 `{ code, message, traceId }`（§17）。
