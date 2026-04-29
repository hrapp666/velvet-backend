# Velvet Backend · Spring Boot 3.5 + JDK 21

> 约会 + 二手寄售双形态社区的后端单体服务。
> Touch what was touched. 私藏，流转。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 框架 | Spring Boot 3.5.0 + Spring Cloud 2024.0.1 + Spring Cloud Alibaba 2023.0.3.2 |
| JDK | Eclipse Temurin **21**（不可降级） |
| 数据库 | AWS RDS MySQL **8.4.7**（版本锁定 · `utf8mb4_0900_ai_ci`） |
| 缓存 | Redis 7（Lettuce 客户端 · session / 限流 / JWT 黑名单） |
| 消息队列 | Apache Kafka 3.8（KRaft 模式 · 事件驱动） |
| 对象存储 | AWS S3（bucket `hr-ai-amzn` · region `ap-southeast-1`） |
| 数据库迁移 | Flyway（V1~V15 · 启动自动跑） |
| 鉴权 | JJWT 0.12.6（access 30min + refresh 7d） |
| ORM | Spring Data JPA + Hibernate 6（`ddl-auto: validate`） |
| 反代 | Nginx + Let's Encrypt |
| 服务注册 | Nacos 2.4.3（可选） |
| 监控 | Spring Actuator + Prometheus（`/actuator/prometheus`） |

---

## 快速开始

完整对接手册见 [`MOFAN_ONBOARDING.md`](https://github.com/huangji6693-max/velvet/blob/main/MOFAN_ONBOARDING.md)（在 monorepo 根目录），下面是最短路径：

```bash
# 1. 拉完整 monorepo（含 docker-compose / nginx / 前端）
git clone https://github.com/huangji6693-max/velvet.git
cd velvet

# 2. 配 .env（必填 DB_URL / REDIS_PASS / JWT_SECRET / S3_*）
cp .env.example .env
vi .env

# 3. 启动核心服务（不含本地 mysql/minio · 用 AWS 托管）
docker compose up -d

# 4. 验证
curl https://api.your-domain.com/api/v1/health
docker compose logs backend --tail 50
```

---

## API 端点

20 个 Controller，全部前缀 `/api/v1`：

| Controller | 路径 | 用途 |
|---|---|---|
| AuthController | `/api/v1/auth/{register,login,apple,refresh,logout,me}` | 鉴权 |
| UserController | `/api/v1/users/**` | 用户资料 |
| MomentController | `/api/v1/moments/**` | 动态 feed |
| CommentController | `/api/v1/comments/**` | 评论 |
| SocialController | `/api/v1/{like,favorite,follow}` | 互动 |
| ChatController | `/api/v1/chat/**` | 私信 |
| MerchantController | `/api/v1/merchants/**` | 商家 |
| OrderController | `/api/v1/orders/**` | 订单 |
| OrderReviewController | `/api/v1/reviews/**` | 评价 |
| PaymentController | `/api/v1/payments/**` | 支付 |
| PaymentNotifyController | `/api/v1/payments/notify/**` | 支付回调（验签内部） |
| WalletController | `/api/v1/wallet/**` | 钱包 |
| UploadController | `/api/v1/upload/presign` | S3 预签名 URL |
| NotificationController | `/api/v1/notifications/**` | 通知 |
| ReportController | `/api/v1/reports/**` | 举报 |
| BlockController | `/api/v1/blocks/**` | 拉黑 |
| SearchController | `/api/v1/search/**` | 搜索 |
| AdminStatsController | `/api/v1/admin/stats` | 后台统计 |
| HealthController | `/api/v1/health` | 健康检查 |

WebSocket：`/ws/chat`（握手时 JWT 在 query string）

---

## 目录结构

```
velvet-backend/
├── src/main/java/com/velvet/backend/
│   ├── controller/      # HTTP API 入口（20 个 Controller）
│   ├── service/         # 业务逻辑 + 事务边界
│   ├── repository/      # JPA Repository
│   ├── entity/          # JPA Entity
│   ├── dto/             # 请求/响应 DTO
│   ├── security/        # Spring Security + JWT 配置
│   ├── config/          # WebSocket / CORS / Redis 等配置
│   ├── websocket/       # ChatWebSocketHandler
│   ├── event/           # Kafka 事件 payload
│   └── exception/       # 全局异常 + 错误码
├── src/main/resources/
│   ├── application.yml          # 主配置（全部从 ENV 读）
│   ├── application-dev.yml      # 本地覆盖（不入容器）
│   ├── bootstrap.yml            # Nacos 启动配置
│   └── db/migration/            # Flyway V1~V15
├── Dockerfile                   # 多阶段构建（maven build → jre runtime）
└── pom.xml
```

---

## 本地开发

```bash
# 启动 MySQL + Redis（用本地 docker）
docker run -d --name velvet-mysql -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=velvet \
  -e MYSQL_USER=velvet -e MYSQL_PASSWORD=velvet \
  mysql:8.4.7

docker run -d --name velvet-redis -p 6379:6379 redis:7-alpine

# 跑应用
cd velvet-backend
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-DDB_PASS=velvet -DREDIS_PASS= -DJWT_SECRET=$(openssl rand -base64 64)"
```

或者用 `application-dev.yml`：

```yaml
# src/main/resources/application-dev.yml（不入容器，本地用）
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/velvet?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8
    password: velvet
velvet:
  jwt:
    secret: dev-secret-at-least-32-bytes-long-for-jwt-signing-please-replace
```

---

## 测试

```bash
./mvnw clean verify              # 单元测试 + 集成测试
./mvnw test                      # 仅单元
./mvnw checkstyle:check          # 代码风格
```

---

## 安全约定

- 所有错误响应**不含**详细 message / stacktrace（`server.error.include-message: never`）
- 所有受保护端点必须 `@PreAuthorize` 或 `SecurityConfig` 显式放行
- 密钥不入代码 → 全部从 `${ENV:default}` 读
- JWT secret 必须 ≥256 bit（`openssl rand -base64 64`）
- Apple Sign-In 必须校验 `aud` claim 与 `APPLE_BUNDLE_ID` 一致
- CORS 生产必须显式列白名单（`VELVET_CORS_EXTRA`），关 localhost（`VELVET_CORS_ALLOW_LOCALHOST=false`）

---

## 常见问题

完整 FAQ 见 [`MOFAN_ONBOARDING.md` 第 8 节](https://github.com/huangji6693-max/velvet/blob/main/MOFAN_ONBOARDING.md#8--常见问题-faq)。

| 问题 | 解决 |
|---|---|
| Flyway checksum mismatch | `DELETE FROM flyway_schema_history WHERE version='X'` 重启 |
| backend unhealthy | 看日志缺哪个 ENV / 网络是否通 RDS / Redis / MSK |
| 上传 S3 失败 | 检查 `S3_PATH_STYLE=false`、region 一致、IAM `s3:PutObject` 权限 |
| WebSocket 连不上 | 检查 Nginx `Upgrade: websocket` header 转发 + 用 `wss://` |
| 中文乱码 | RDS 字符集必须 `utf8mb4_0900_ai_ci`，连接串带 `characterEncoding=utf8mb4` |

---

## License

私有 / 商业项目（Velvet by 黄哥团队）
