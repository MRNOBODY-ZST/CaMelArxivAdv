# API 约定

## 基础约定

- 前缀：`/api/v1`
- 内容：JSON，UTF-8，字段使用 lower camel case。
- 时间：RFC 3339 UTC，例如 `2026-08-05T13:45:22Z`。
- 分页：后续列表统一 `page`、`size`、`sort`，响应返回 items 与分页元数据。
- 追踪：接受 `X-Request-Id`，无值时生成；错误响应返回 `traceId`。
- OpenAPI：`/api/openapi.json`；Swagger UI：`/api/docs`。

## 当前公开端点

### `GET /api/v1/system/health`

无需认证，用于前端和部署验收。

```json
{
  "status": "UP",
  "checkedAt": "2026-08-05T13:45:22.916688467Z"
}
```

Actuator readiness/liveness 只用于容器探针，不应作为业务 API 扩展。

## 统一错误

错误不会返回 Java 类名或堆栈。结构：

```json
{
  "code": "VALIDATION_ERROR",
  "message": "请求参数无效",
  "traceId": "0123456789abcdef0123456789abcdef",
  "timestamp": "2026-08-05T13:45:22Z",
  "fieldErrors": {
    "email": "格式无效"
  }
}
```

常用状态：400 参数/状态无效，401 未认证，403 权限不足，404 不存在，409 并发或唯一冲突，422 可识别但不可处理，429 限速，500 未预期错误，503 依赖不可用。

## 安全策略

Phase 1 仅放行健康、Actuator health/info 和 OpenAPI 文档；其余请求默认要求认证。Phase 2 将增加短期 Access JWT、Refresh Token 轮换、方法级权限和登录限速。

Access Token 只保存在前端内存；Refresh Token 使用 HttpOnly/Secure/SameSite Cookie。API 不在 URL、日志或错误信息中返回 Secret。

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
