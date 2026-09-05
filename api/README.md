# weaveora-api — 织影后端（Java 21 + Spring Boot 3.4.5 + Modulith）

模块（§16.2，包即边界）：`identity`（账号/工作区/JWT）· `project`（项目/Brief + `ProjectContextPort`）
· `director`（LLM JSON 导演层：generate → 校验 → PromptRevision/ShotDraft → approve）· `shared`。

## 本地起（Windows Git Bash）

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
export WEAVEORA_JWT_SECRET="$(openssl rand -hex 32)"   # 必填，勿入库
cd api && mvn spring-boot:run      # :8080，dev profile 连本机 PG weaveora_dev + Redis db1
```

前置：PG `weaveora_dev`（localhost:5432 postgres/postgres）；Redis 走 VPS 隧道
`ssh -f -N -L 6379:127.0.0.1:6379 root@sysou.com` 或用本机临时实例（db1）。
可 `make api-dev`（Makefile 已设 JDK21）。

## 导演层 LLM（W2）

- OpenAI Compatible：配置 `WEAVEORA_LLM_BASE_URL / WEAVEORA_LLM_API_KEY / WEAVEORA_LLM_MODEL`（或
  `application-local.yml` 的 `weaveora.llm.*`），三者齐备自动启用真实 LLM；
- 未配置 → `StubDirectorLlm` 离线兜底（`source=stub`，UI 标注“示例方案”），可离线演示全链路。
- 校验器纯函数 `DirectorPlanValidator`（单测 9 例，§10.3）；方案 JSON Schema：`packages/schemas/director.schema.json`。

## 测试

```bash
mvn test    # 校验器等纯单测（§27：Director 校验器不依赖 DB）
```

E2E（W1/W2A 已验）：注册→建项目→Brief→generate（stub，12s→4 镜和=12s）→PATCH 保留已确认镜头→
单镜 approve→整版 approve→已确认改稿 409 REVISION_LOCKED；越权/缺头 400/403。
