# API 约定

## 基础约定

- 前缀：`/api/v1`
- 内容：JSON，UTF-8，字段使用 lower camel case。
- 时间：RFC 3339 UTC，例如 `2026-08-05T13:45:22Z`。
- 分页：列表统一 `page`（从 1 开始）与 `pageSize`（最大 100），响应返回 `items`、`page`、`pageSize`、`total` 和 `totalPages`。
- 追踪：接受 `X-Trace-Id`，无值时生成；响应头和错误体均返回同一 `traceId`。
- OpenAPI：`/api/openapi.json`；Swagger UI：`/api/docs`。

## 公开端点

### `GET /api/v1/system/health`

无需认证，用于前端和部署验收。

```json
{
  "status": "UP",
  "checkedAt": "2026-08-05T13:45:22.916688467Z"
}
```

Actuator readiness/liveness 只用于容器探针，不应作为业务 API 扩展。

### 认证入口

`POST /api/v1/auth/login` 接受 `{"principal":"用户名或邮箱","password":"..."}`。成功响应包含 10 分钟 Access Token 和当前用户，同时通过 `Set-Cookie` 写入 refresh；refresh 原值不会进入 JSON。

`POST /api/v1/auth/refresh` 仅使用 refresh Cookie，成功时轮换 Cookie 并返回新的 Access Token。已轮换值再次出现时返回 401，并撤销同一 token family。

`POST /api/v1/auth/logout` 撤销当前 refresh family 并清除 Cookie。即使 Cookie 已缺失，调用也保持幂等。

## 已认证端点

### 当前账号

- `GET /api/v1/auth/me`：返回数据库中的实时账号、角色、权限和 `mustChangePassword`，不返回邮箱或凭据。
- `POST /api/v1/auth/change-password`：接受 `currentPassword`、`newPassword`；成功返回 204，使当前 Access Token 与全部 Refresh Token 立即失效。

初始/重置密码会令 `mustChangePassword=true`，完成改密前业务权限数组为空。

### 用户管理

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /api/v1/users` | `user:read` | `search`、`status`、`page`、`pageSize` 筛选 |
| `POST /api/v1/users` | `user:create` | 创建账号、初始密码和角色，强制首次改密 |
| `PUT /api/v1/users/{id}` | `user:update` | 更新邮箱、显示名和角色 |
| `POST /api/v1/users/{id}/disable` | `user:disable` | 停用并使会话失效 |
| `POST /api/v1/users/{id}/enable` | `user:disable` | 启用账号 |
| `POST /api/v1/users/{id}/reset-password` | `user:update` | 重置密码、强制改密并使会话失效 |

用户响应不包含密码哈希或 refresh 数据。只有有效 `SUPER_ADMIN` 能创建、编辑、重置或启停 `SUPER_ADMIN` 账号；最后一个有效 `SUPER_ADMIN` 不允许被停用或剥离角色。

### 角色、权限和审计

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /api/v1/roles` | `role:read` | 角色、用户数及权限集合 |
| `GET /api/v1/permissions` | `role:read` | 26 项只读权限目录 |
| `POST /api/v1/roles` | `role:manage` | 创建自定义角色 |
| `PUT /api/v1/roles/{id}` | `role:manage` | 更新名称、说明和授权 |
| `DELETE /api/v1/roles/{id}` | `role:manage` | 删除未使用的自定义角色；系统角色不可删除 |
| `GET /api/v1/audit-logs` | `audit:read` | 支持 `from`、`to`、`actorId`、`action`、`resource`、`result` 和分页筛选 |

## 统一错误

错误不会返回 Java 类名或堆栈。结构：

```json
{
  "type": "validation_error",
  "title": "Validation failed",
  "status": 400,
  "detail": "Request contains invalid fields",
  "instance": "/api/v1/users",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": {
    "email": ["must be a well-formed email address"]
  }
}
```

常用状态：400 参数/状态无效，401 未认证，403 权限不足，404 不存在，409 并发或唯一冲突，422 可识别但不可处理，429 限速，500 未预期错误，503 依赖不可用。

## 安全策略

仅登录、refresh、logout、健康、追踪占位和 API 文档公开；其余请求默认要求 Bearer JWT。受保护请求实时校验数据库账号状态与 `tokenVersion`；管理 API 在 HTTP 边界和控制器方法两个层级校验精确权限代码。

Access Token 只保存在前端内存；Refresh Token 使用 HttpOnly/Secure/SameSite Cookie。连续失败默认在 15 分钟窗口内达到 5 次后触发账号与 IP 双维度限流。API 不在 URL、日志或错误信息中返回 Secret。角色矩阵与失效语义见 [RBAC.md](RBAC.md)。

## 异步任务与消息

长任务 API 返回任务 ID；状态由 `jobs` 状态机管理。SSE 计划使用 `/api/v1/jobs/{id}/events`，断线后客户端根据最后事件 ID 续传，必要时轮询任务详情。

RabbitMQ 信封固定包含：

```json
{
  "version": 1,
  "messageId": "uuid",
  "type": "WORKER_HEARTBEAT",
  "jobId": null,
  "idempotencyKey": "heartbeat:worker:timestamp",
  "traceId": "32-hex",
  "occurredAt": "2026-08-05T13:45:22Z",
  "payload": {}
}
```

不支持的 `version` 必须拒绝；消费者只有在幂等写入成功后 ACK。

## 追踪入口预留

Nginx 将 `/t/` 转发后端。后续打开像素和点击 Token 必须签名、限时、不可枚举；点击目标只能来自活动快照中的已验证 URL，禁止把任意查询参数直接作为重定向地址。
