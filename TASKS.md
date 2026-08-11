# CaMelArxivAdv 任务状态

## 阶段 1：工程基础

- [x] 根目录安全守卫与构建仓库配置
- [x] Spring Boot PostgreSQL/Redis/Kafka/Flyway/Actuator/OpenAPI 基础
- [x] 统一错误格式、Trace ID 与健康 API
- [x] Flyway 从空库迁移和索引基线
- [x] Python Worker 工程、配置、日志与健康心跳
- [x] Vue 3 严格模式工程和 DesignSkill 组件适配基础
- [x] Sidebar with header 应用外壳和 Bento Grid 首页骨架
- [x] PostgreSQL/Redis/Kafka/MinIO/Mailpit/GreenMail/Nginx Compose
- [x] 阶段 1 测试、构建、容器健康和文档

## 阶段 2：认证与 RBAC

- [x] 用户、角色、权限和默认角色迁移
- [x] JWT Access Token 和 Refresh Token 轮换
- [x] 登录限制、改密、重置、注销和强制失效
- [x] 方法级授权、邮箱脱敏与审计
- [x] 登录页、内存 Token Store、刷新锁和权限路由
- [x] 阶段 2 验证

## 阶段 3：arXiv 发现与导入

- [x] 分类离线快照、OAI ListSets 同步和分类树 API
- [x] arXiv 查询预览、缓存与全局三秒限速
- [x] 保存查询和异步导入任务
- [x] OAI-PMH resumption token 同步
- [x] 任务状态机、幂等、SSE 和轮询回退
- [x] 论文发现、任务和论文库页面
- [x] 阶段 3 验证

## 阶段 4：Source 解析

- [x] 官方主机白名单下载器
- [x] 安全归档防护和临时目录清理
- [x] TeX 主文件与 include 发现
- [x] 作者、邮箱、机构与置信度提取
- [x] Kafka 手工提交、重试主题、DLT 和幂等回写
- [x] 联系人、论文详情和提取记录页面
- [x] 阶段 4 验证

## 阶段 5：数据统计

- [x] 采集、论文、作者和联系人聚合
- [x] ECharts 图表、全局筛选和 URL 同步
- [x] 数据口径、导出、空/错/加载状态
- [x] 阶段 5 验证

## 阶段 6：模板与邮件协议

- [x] 安全邮件模板、变量、版本和预览
- [x] MinIO 图片上传
- [x] SMTP Secret 加密和账户测试
- [x] Mailpit 测试发送
- [x] 模板和 SMTP 页面
- [x] 公网 SMTP/IMAP/POP3 TLS 策略、账户管理和只读邮件头预览
- [x] 阶段 6 验证

## 阶段 7：活动发送

- [ ] Segment 和收件人不可变快照
- [ ] 活动审核状态机
- [ ] 抑制、退订和频率保护
- [ ] Mail Worker、限速、重试和幂等
- [ ] 活动向导与详情页面
- [ ] 阶段 7 验证

## 阶段 8：追踪

- [ ] 签名打开像素和 Likely Human 分类
- [ ] 受控点击 Token 和开放重定向防护
- [ ] 活动、链接和域名统计
- [ ] 隐私保留清理
- [ ] 阶段 8 验证

## 阶段 9：完善发布

- [ ] 完整 E2E 主流程
- [ ] 安全、隐私、可访问性和响应式 QA
- [ ] 全量测试、lint、类型检查和构建
- [ ] Docker Compose 从空环境启动与健康验证
- [ ] 完整文档和最终验收清单
