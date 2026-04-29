# Velvet 后端 · AWS 生产部署文档

> 基于莫凡 AWS 账号实际配置（**ap-east-1 香港 region** · RDS MySQL 8.4.7 + ElastiCache Redis Serverless + MSK Serverless Kafka + S3）。
> 完整对接手册（FAQ / API 端点清单 / WebSocket / 切流量）见仓库根 `MOFAN_ONBOARDING.md`，本文是配置 + 库 + Topic 的执行参考。
> ⚠️ 真实凭证（DB_PASS / S3_ACCESS_KEY / S3_SECRET_KEY / JWT_SECRET）**不进 git**，黄哥私聊发给你，写到 EC2 上的 `.env` 文件。

---

## 一、AWS 资源（已就绪 · ap-east-1）

| 资源 | 实例标识 | endpoint |
|---|---|---|
| **RDS MySQL 8.4.7** | `ai-agent` | `ai-agent.cfiecm82wxyj.ap-east-1.rds.amazonaws.com:3306` |
| **ElastiCache Redis Serverless** | `ai-agent-redis-veqiit` | `ai-agent-redis-veqiit.serverless.ape1.cache.amazonaws.com:6379` |
| **MSK Serverless Kafka** | `boot-pjl2chfv` | `boot-pjl2chfv.c3.kafka-serverless.ap-east-1.amazonaws.com:9098` |
| **S3 Bucket** | `ai-agent-619785635337-ap-east-1-an` | `https://s3.ap-east-1.amazonaws.com` |
| **VPC** | 上述 4 个资源所在 VPC | EC2 必须放进同一 VPC + Security Group 互通 |

> ⚠️ **网络坑**：RDS / ElastiCache / MSK 都是 VPC-only 资源，公网不通。EC2 不在同一 VPC = 全部 connection refused。

---

## 二、EC2 准备

| 项 | 推荐 |
|---|---|
| 实例类型 | t3.medium 起步（2vCPU / 4GB） |
| OS | Ubuntu 22.04 LTS |
| VPC | 与 RDS / ElastiCache / MSK 同一 VPC |
| Security Group · 入站 | 80 / 443 / 22 (限制 IP) |
| Security Group · 出站 | RDS:3306 / Redis:6379 / Kafka:9098 全放行 |
| IAM Role | 附 `kafka-cluster:*`（用于 MSK IAM 鉴权） |
| 域名 | Route 53 解析到 EC2 公网 IP（例 `api.your-domain.com`） |

```bash
# 装依赖
sudo apt update && sudo apt install -y git docker.io docker-compose-plugin curl
sudo systemctl enable --now docker
sudo usermod -aG docker $USER
# 重新登录使 docker 组生效
```

---

## 三、MySQL 库初始化

### 3.1 RDS 参数组（创建实例时已设定，确认即可）

| 参数 | 值 | 说明 |
|---|---|---|
| `character_set_server` | `utf8mb4` | 中文 + emoji |
| `collation_server` | **`utf8mb4_0900_ai_ci`** | MySQL 8.4 默认 |
| `time_zone` | `+00:00` | 应用层 UTC |
| `require_secure_transport` | `ON` | 强制 SSL |

### 3.2 创建数据库

用 admin 账号（密码黄哥另发）连 RDS 执行：

```sql
CREATE DATABASE velvet
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

-- 业务账号（可选 · 不想用 admin 直连业务库时）
CREATE USER 'velvet'@'%' IDENTIFIED BY '<业务库密码>';
GRANT ALL PRIVILEGES ON velvet.* TO 'velvet'@'%';
FLUSH PRIVILEGES;
```

> 也可以直接用 `admin` 账号让应用连，省一步建账号。`.env` 里 `DB_USER=admin` 即可。

### 3.3 表结构

**不要手工建表。** 应用启动时 Flyway 自动按 V1→V15 顺序执行 `velvet-backend/src/main/resources/db/migration/`：

```
V1__init.sql                              # 用户 / 动态 / 评论核心表
V2__chat_and_users.sql                    # 聊天 + 用户扩展
V3__orders.sql                            # 订单
V4__merchants_and_payments.sql            # 商家 + 支付
V5__wallets_and_withdrawals.sql           # 钱包 + 提现
V6__reports.sql                           # 举报
V7__moments_geo.sql                       # 同城 geo 索引
V8__wallet_version_and_comment_user_index.sql
V9__order_shipping_address.sql
V10__comment_likes.sql
V11__blocks.sql
V12__blocks_reason.sql
V13__apple_user_id.sql                    # Apple Sign-In 唯一标识
V14__conversations_last_message_id.sql
V15__messages_media_and_ref.sql           # 消息媒体 + 引用回复
```

应用配置：
- `spring.flyway.baseline-on-migrate=true`（老库迁移友好）
- `spring.jpa.hibernate.ddl-auto=validate`（Hibernate 只校验不改 schema）

### 3.4 SSL 证书

RDS 强制 SSL，需把 AWS RDS root CA 挂到容器：

```bash
# EC2 上下载（一次性）
sudo mkdir -p /etc/velvet
sudo curl -o /etc/velvet/global-bundle.pem \
  https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
sudo chmod 644 /etc/velvet/global-bundle.pem
```

`docker-compose.yml` 里 backend 服务挂 `-v /etc/velvet/global-bundle.pem:/global-bundle.pem:ro`（如果没挂上，连接串里去掉 `&trustCertificateKeyStoreUrl=...&verifyServerCertificate=true`，只保留 `useSSL=true&requireSSL=true`）。

---

## 四、Kafka Topic（MSK Serverless · IAM 鉴权）

### 4.1 Topic 清单

| Topic | 分区 | 副本 | 用途 |
|---|---|---|---|
| `velvet.orders` | 3 | 2 | 订单事件（创建 / 支付 / 退款） |
| `velvet.notifications` | 3 | 2 | 通知事件（推送 / 站内信） |

### 4.2 手工创建（MSK Serverless 需要）

应用 `KafkaAdmin` 默认会自动创建，但 MSK Serverless 需要 IAM 显式授权 `kafka-cluster:CreateTopic`，没授权的话先手动建：

```bash
# EC2 上装 kafka cli
wget https://archive.apache.org/dist/kafka/3.8.0/kafka_2.13-3.8.0.tgz
tar xzf kafka_2.13-3.8.0.tgz
cd kafka_2.13-3.8.0

# IAM 鉴权 jar
wget -P libs/ https://github.com/aws/aws-msk-iam-auth/releases/download/v2.2.0/aws-msk-iam-auth-2.2.0-all.jar

# client.properties
cat > client.properties << 'EOF'
security.protocol=SASL_SSL
sasl.mechanism=AWS_MSK_IAM
sasl.jaas.config=software.amazon.msk.auth.iam.IAMLoginModule required;
sasl.client.callback.handler.class=software.amazon.msk.auth.iam.IAMClientCallbackHandler
EOF

# 建 topic
bin/kafka-topics.sh \
  --bootstrap-server boot-pjl2chfv.c3.kafka-serverless.ap-east-1.amazonaws.com:9098 \
  --command-config client.properties \
  --create --topic velvet.orders --partitions 3 --replication-factor 2

bin/kafka-topics.sh \
  --bootstrap-server boot-pjl2chfv.c3.kafka-serverless.ap-east-1.amazonaws.com:9098 \
  --command-config client.properties \
  --create --topic velvet.notifications --partitions 3 --replication-factor 2

# 验证
bin/kafka-topics.sh \
  --bootstrap-server boot-pjl2chfv.c3.kafka-serverless.ap-east-1.amazonaws.com:9098 \
  --command-config client.properties --list
```

EC2 IAM Role 需附以下 policy（建议把 Resource 限定到具体 cluster ARN）：

```json
{
  "Effect": "Allow",
  "Action": [
    "kafka-cluster:Connect",
    "kafka-cluster:DescribeCluster",
    "kafka-cluster:WriteData",
    "kafka-cluster:ReadData",
    "kafka-cluster:CreateTopic",
    "kafka-cluster:DescribeTopic",
    "kafka-cluster:DescribeGroup",
    "kafka-cluster:AlterGroup"
  ],
  "Resource": "arn:aws:kafka:ap-east-1:619785635337:*/*"
}
```

---

## 五、S3 对象存储

| 项目 | 值 |
|---|---|
| Bucket | `ai-agent-619785635337-ap-east-1-an` |
| Region | `ap-east-1` |
| Endpoint | `https://s3.ap-east-1.amazonaws.com` |
| Path-style | `false`（AWS 用 virtual-hosted） |
| IAM 权限 | `s3:PutObject` / `s3:GetObject` / `s3:DeleteObject` / `s3:ListBucket` |
| AK / SK | 黄哥私聊发给你 |

---

## 六、`.env`（EC2 上 `/root/velvet/.env`）

⚠️ `.env` **不进 git**（已在 `.gitignore`）。下面是莫凡场景的填值模板，真实密钥处占位 `<黄哥私聊>`：

```env
# ─────────── 数据库（AWS RDS MySQL 8.4.7 · ap-east-1）──────────
DB_URL=jdbc:mysql://ai-agent.cfiecm82wxyj.ap-east-1.rds.amazonaws.com:3306/velvet?useSSL=true&requireSSL=true&verifyServerCertificate=true&trustCertificateKeyStoreUrl=file:///global-bundle.pem&serverTimezone=UTC&characterEncoding=utf8mb4&useUnicode=true&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true
DB_USER=admin
DB_PASS=<黄哥私聊>
DB_NAME=velvet

# ─────────── Redis（ElastiCache Serverless · 无密码）─────────
REDIS_HOST=ai-agent-redis-veqiit.serverless.ape1.cache.amazonaws.com
REDIS_PORT=6379
REDIS_PASS=

# ─────────── JWT（必须新生成 · 不复用任何环境）────────────────
JWT_SECRET=<黄哥私聊 · openssl rand -base64 64 生成>
JWT_EXPIRATION_MS=1800000
JWT_REFRESH_EXPIRATION_MS=604800000

# ─────────── S3（AWS · ap-east-1）─────────────────────────────
S3_ENDPOINT=https://s3.ap-east-1.amazonaws.com
S3_ENDPOINT_PUBLIC=
S3_BUCKET=ai-agent-619785635337-ap-east-1-an
S3_REGION=ap-east-1
S3_ACCESS_KEY=<黄哥私聊>
S3_SECRET_KEY=<黄哥私聊>
S3_PATH_STYLE=false
CDN_BASE_URL=https://ai-agent-619785635337-ap-east-1-an.s3.ap-east-1.amazonaws.com

# ─────────── Kafka（MSK Serverless · IAM 鉴权）────────────────
KAFKA_SERVERS=boot-pjl2chfv.c3.kafka-serverless.ap-east-1.amazonaws.com:9098
KAFKA_SECURITY_PROTOCOL=SASL_SSL
KAFKA_SASL_MECHANISM=AWS_MSK_IAM
KAFKA_SASL_JAAS_CONFIG=software.amazon.msk.auth.iam.IAMLoginModule required;
KAFKA_SASL_CALLBACK=software.amazon.msk.auth.iam.IAMClientCallbackHandler

# ─────────── 支付 ─────────────────────────────────────────────
VELVET_PAYMENT_MOCK_ENABLED=false
PAYMENT_DEFAULT_PROVIDER=WECHAT
PAYMENT_COMMISSION_RATE=0.06
PAYMENT_NOTIFY_BASE_URL=https://api.your-domain.com
WECHAT_MCH_ID=
WECHAT_APP_ID=
WECHAT_API_KEY=
ALIPAY_APP_ID=
ALIPAY_PRIVATE_KEY=

# ─────────── Apple Sign-In ────────────────────────────────────
APPLE_BUNDLE_ID=com.hrapp.velvet
APPLE_VERIFY_AUDIENCE=true

# ─────────── CORS / 域名 / 管理员 ─────────────────────────────
VELVET_CORS_EXTRA=https://your-frontend.com
VELVET_CORS_ALLOW_LOCALHOST=false
API_DOMAIN=api.your-domain.com
ADMIN_USERNAMES=huangji,admin,root
ADMIN_USER_IDS=1

# ─────────── 端口 ─────────────────────────────────────────────
PORT=8080
SPRING_PROFILES_ACTIVE=
```

---

## 七、部署步骤

### 7.1 拉 monorepo

```bash
mkdir -p /root/velvet && cd /root/velvet
git clone https://github.com/huangji6693-max/velvet.git .
ls
# velvet-backend/  velvet-flutter/  docker-compose.yml  nginx/  Makefile  .env.example
```

### 7.2 写 `.env`

```bash
cp .env.example .env
vi .env   # 填第六节的真实值（黄哥私聊给你的密钥）
```

### 7.3 配 Nginx 域名 + 证书

```bash
# 替换 nginx 配置里的旧域名
sed -i 's/agent.ylctkx9s.work/api.your-domain.com/g' nginx/velvet.conf

# 临时启 nginx 处理 ACME challenge
docker compose up -d nginx
docker compose run --rm certbot certonly --webroot --webroot-path=/var/www/certbot \
  -d api.your-domain.com --agree-tos --register-unsafely-without-email
docker compose restart nginx
```

### 7.4 启动核心服务

```bash
docker compose up -d
# 默认启动：redis(可不启 · 已切 ElastiCache) + kafka(可不启 · 已切 MSK) + backend + nginx + certbot
# 不带 self-hosted profile = 不启 mysql/minio 容器（用 AWS RDS/S3）

docker compose logs -f backend
```

> 因为已经全切 AWS 托管的 Redis + Kafka，如果想精简，把 docker-compose.yml 里的 `redis` 和 `kafka` 服务停掉也可以（应用通过环境变量直接连 AWS endpoint）。第一次部署建议**先全启**便于排查，验证完再裁剪。

预期 30~90 秒看到：
- Flyway 跑完 V1~V15
- Hibernate schema validate 通过
- `Tomcat started on port 8080`
- `Started VelvetBackendApplication`

### 7.5 健康检查

```bash
docker compose ps                                          # 全 healthy/running
curl https://api.your-domain.com/api/v1/health             # {"status":"UP",...}
curl https://api.your-domain.com/actuator/health           # db/redis/kafka 全 UP
```

---

## 八、上线前 12 项必跑清单

| # | 检查项 | 命令 / 方式 |
|---|---|---|
| 1 | 数据库连接 | `docker compose logs backend \| grep "HikariPool-1 - Start completed"` |
| 2 | Flyway 迁移 V1~V15 全过 | `docker compose logs backend \| grep "Successfully applied"` |
| 3 | Hibernate schema validate 无错 | 日志无 `Schema-validation` |
| 4 | Redis 连通 | `curl /actuator/health` 看 `redis: UP` |
| 5 | Kafka 连通 | 日志无 `Connection to node ... could not be established` |
| 6 | S3 上传 | 前端发带图动态 → bucket 看新对象 |
| 7 | 健康端点 | `curl /api/v1/health` → 200 |
| 8 | HTTPS 证书 | 浏览器访问无证书警告 |
| 9 | CORS | 前端域名调 API 不报跨域 |
| 10 | WebSocket | 登录 → 进聊天 → 发消息 → 对方收到 |
| 11 | JWT 鉴权 | 未登录访问受保护端点 → 401 |
| 12 | 管理员 | `huangji` 注册自动 admin → `/api/v1/admin/stats` 200 |

---

## 九、常见坑

| 现象 | 解法 |
|---|---|
| backend unhealthy | `docker compose logs backend --tail 100` 看 5 大原因：① `.env` 缺 DB_PASS / JWT_SECRET / S3_*  ② RDS Security Group 没放 EC2（`telnet ai-agent.cfiecm82wxyj.ap-east-1.rds.amazonaws.com 3306` 不通）③ Flyway checksum 不一致 ④ Kafka 端口必须 **9098** 不是 9092 ⑤ JWT_SECRET < 32 字节 |
| Flyway checksum mismatch | 不要改 migration 文件 → `DELETE FROM flyway_schema_history WHERE version='X'` 后重启 |
| 中文表情乱码 | RDS 参数组 `collation_server=utf8mb4_0900_ai_ci`（不是 `utf8mb4_unicode_ci`） |
| S3 上传 403 | ① `S3_PATH_STYLE=false`（AWS 必须）② `S3_REGION=ap-east-1`（与 bucket 一致）③ IAM 缺 `s3:PutObject` |
| Kafka SASL 认证失败 | EC2 IAM Role 没附 `kafka-cluster:*` 权限 → 见第 4.2 节 policy |
| RDS SSL 握手失败 | `global-bundle.pem` 没下载或没挂载到 `/global-bundle.pem` → 见第 3.4 节 |
| WebSocket 连不上 | ① Nginx 缺 `Upgrade: websocket` header ② 前端 URL 必须 `wss://` ③ JWT 在 query string 不在 header |

---

## 十、回滚 / 凭证轮换

- **应用回滚**：`docker compose down && git checkout <旧 tag> && docker compose up -d`
- **DB 回滚**：不要执行 Flyway undo。新版本若新增非破坏性 DDL，旧版本启动会因 `ddl-auto=validate` 失败但不会损坏数据
- **凭证泄露**（JWT_SECRET / S3 AK SK / DB_PASS）：
  - DB 密码：RDS 控制台 modify → 重置 master password → 改 `.env` → `docker compose restart backend`
  - S3 AK/SK：IAM 控制台 deactivate → 创建新 AK/SK → 改 `.env` → 重启
  - JWT_SECRET：改 `.env` → 重启 → 强制所有 refresh token 失效（清 Redis `velvet:refresh:*` key）

---

## 十一、附：完整对接手册

| 文档 | 内容 |
|---|---|
| `MOFAN_ONBOARDING.md` | 完整对接手册（API 端点清单 / WebSocket / 切流量 / FAQ）|
| `velvet-backend/DEPLOY.md` | 本文 · 配置 + 库 + Topic 执行参考 |
| `docker-compose.yml` | 容器编排（已串好所有 env） |
| `nginx/velvet.conf` | Nginx 反代 + HTTPS 模板 |
| `.env.example` | env 完整注释模板 |
| `velvet-backend/src/main/resources/db/migration/` | Flyway V1~V15 |

monorepo：`https://github.com/huangji6693-max/velvet.git`

跑不通先翻第八节验证清单 + 第九节常见坑。还卡住找黄哥。
