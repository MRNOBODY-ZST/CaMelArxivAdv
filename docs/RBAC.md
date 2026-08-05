# 认证与 RBAC

## 会话模型

- 用户名和邮箱均可登录，比较前统一按小写处理；失败响应不暴露账号是否存在。
- 密码使用 BCrypt cost 12。新密码至少 12 位，并同时包含大写、小写、数字和符号，且不能包含用户名或邮箱主体。
- Access Token 为 HS256 JWT，默认有效期 10 分钟，只由前端 Pinia 内存持有。后端每次受保护请求都会重新读取账号状态、`tokenVersion` 和实时权限。
- Refresh Token 是 32 字节随机值，只通过 `HttpOnly` Cookie 传输；数据库仅保存 SHA-256 摘要。每次 refresh 都进行事务内单次轮换，旧值重放会撤销整个 token family。
- 禁用账号、重置或修改密码、改变角色授权都会增加相关用户的 `tokenVersion` 并撤销 refresh 会话，已签发的 access token 随即失效。
- 初始密码或管理员重置后的账号在完成改密前，其有效业务权限集合为空；只允许已认证的账号信息与改密流程。

生产 Cookie 固定为 `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`。仅 `docker-compose.dev.yml` 为 localhost HTTP 设置 `Secure=false`。

## 默认角色矩阵

系统在 Flyway V5 中幂等创建 26 个权限和 5 个不可删除的系统角色。`SUPER_ADMIN` 拥有全部权限；其他默认授权如下。

| 角色 | 默认权限 |
|---|---|
| `SUPER_ADMIN` | 全部 26 项权限 |
| `ADMIN` | `user:read`, `user:create`, `user:update`, `user:disable`, `role:read`, `paper:read`, `paper:import`, `paper:delete`, `contact:read_masked`, `contact:read_full`, `contact:verify`, `contact:export`, `job:manage`, `template:read`, `template:manage`, `smtp:read`, `smtp:manage`, `campaign:read`, `campaign:create`, `campaign:approve`, `campaign:send`, `campaign:pause`, `analytics:read` |
| `CAMPAIGN_MANAGER` | `paper:read`, `contact:read_masked`, `template:read`, `template:manage`, `campaign:read`, `campaign:create`, `campaign:send`, `campaign:pause`, `analytics:read` |
| `DATA_ANALYST` | `paper:read`, `paper:import`, `contact:read_masked`, `contact:export`, `job:manage`, `analytics:read` |
| `VIEWER` | `paper:read`, `campaign:read`, `analytics:read` |

完整权限目录：

```text
user:read user:create user:update user:disable
role:read role:manage
paper:read paper:import paper:delete
contact:read_masked contact:read_full contact:verify contact:export
job:manage
template:read template:manage
smtp:read smtp:manage
campaign:read campaign:create campaign:approve campaign:send campaign:pause
analytics:read audit:read system:manage
```

系统角色允许调整授权但不允许改名或删除，其中 `SUPER_ADMIN` 必须始终保有完整 26 项权限。自定义角色代码必须匹配 `[A-Z][A-Z0-9_]{1,49}`，仍分配给用户时不能删除。只有有效 `SUPER_ADMIN` 能创建、编辑、重置、启停任何 `SUPER_ADMIN` 账号，或分配/移除该角色；普通 `ADMIN` 的用户管理权限不能用于账号接管或提权。最后一个有效 `SUPER_ADMIN` 受到数据库事务和 advisory lock 保护，不能被停用或剥离该角色。

## 管理 API 权限

| 操作 | 权限 |
|---|---|
| 查看用户 | `user:read` |
| 创建用户 | `user:create` |
| 更新用户、分配角色、重置密码 | `user:update` |
| 停用或启用用户 | `user:disable` |
| 查看角色与权限目录 | `role:read` |
| 创建、更新或删除自定义角色 | `role:manage` |
| 查看审计日志 | `audit:read` |

Vue 的路由、导航和按钮会使用同一权限代码隐藏无权入口，但它们只负责用户体验；Spring `@PreAuthorize` 是最终授权边界。

联系人邮箱由独立披露策略处理：`contact:read_full` 返回完整地址，只有 `contact:read_masked` 时返回如 `jo***@example.edu` 的掩码，没有任一权限则拒绝访问。

## 初始管理员

首次启动前设置：

```dotenv
INITIAL_ADMIN_USERNAME=admin
INITIAL_ADMIN_EMAIL=admin@example.invalid
INITIAL_ADMIN_DISPLAY_NAME=Administrator
INITIAL_ADMIN_PASSWORD=<符合密码策略的临时值>
```

引导过程只在没有同名/同邮箱用户时创建一次账号，存储 BCrypt 哈希并授予 `SUPER_ADMIN`。登录后必须立即改密；生产环境随后应从运行时 Secret 中移除 `INITIAL_ADMIN_PASSWORD`。

## 审计边界

登录成功/失败、refresh 轮换/重放、注销、密码修改/重置、用户状态、角色分配、角色授权变化和管理端 HTTP 权限拒绝均写入 `audit_logs`。超级管理员保护会额外写入 `SUPER_ADMIN_MANAGEMENT_DENIED` 业务审计，与 HTTP 层的 `AUTHORIZATION_DENIED` 通过同一 Trace ID 关联但不混淆计数。记录包含操作者、动作、资源、时间、结果、Trace ID、IP 的 HMAC 摘要、截断 User-Agent 和脱敏前后摘要；密码、JWT、Cookie、refresh 原值、密码哈希与密钥不得进入审计、日志或 API 响应。
