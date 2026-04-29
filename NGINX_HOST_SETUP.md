# Velvet · 宿主机 Nginx + Certbot 部署（莫凡 AWS · 2026-04-29 v2）

> nginx 不放 docker，直接装在 EC2 宿主机上。这样配多域名 / 证书 / 改 server_name 都更顺手，不用每次重启容器。

---

## 1. 装 nginx + certbot（Ubuntu 22.04）

```bash
sudo apt update
sudo apt install -y nginx certbot python3-certbot-nginx
sudo systemctl enable --now nginx
nginx -v   # 确认 ≥ 1.18
```

## 2. 放 Velvet 配置

```bash
# 仓库里的两段（limit_req_zone + upstream）必须放 http{} 顶层，所以单独抽一个文件
sudo cp nginx/velvet-aws.conf  /etc/nginx/sites-available/velvet.conf

# 把开头 limit/upstream 段拆出来（也可以直接整文件复制 server{} 部分进 sites-available）
sudo head -n 24 /etc/nginx/sites-available/velvet.conf | sudo tee /etc/nginx/conf.d/velvet-upstream.conf
sudo sed -i '1,24d' /etc/nginx/sites-available/velvet.conf

# 启用站点
sudo ln -s /etc/nginx/sites-available/velvet.conf /etc/nginx/sites-enabled/velvet.conf
sudo rm -f /etc/nginx/sites-enabled/default

# 替换占位域名
sudo sed -i 's/api.your-domain.com/api.实际域名.com/g' /etc/nginx/sites-available/velvet.conf

sudo nginx -t          # 配置语法检查
sudo systemctl reload nginx
```

## 3. 申请 Let's Encrypt 证书

```bash
sudo certbot --nginx \
  -d api.实际域名.com \
  --agree-tos \
  -m your-email@example.com \
  --redirect \
  --non-interactive
```

certbot 会自动改 nginx 配置，加上 443 ssl 块。如果你的 `velvet.conf` 已经写好 ssl，加 `--cert-name` 直接发证不改配置：

```bash
sudo certbot certonly --nginx -d api.实际域名.com --cert-name velvet-api
```

> 自动续期已由 `certbot.timer` (systemd) 接管，每天扫两次：`systemctl status certbot.timer`

## 4. 启动 backend 容器

```bash
cd /opt/velvet  # 或你 clone 的位置
docker compose -f docker-compose.aws.yml up -d backend
docker compose logs -f backend
```

backend 端口 `127.0.0.1:8080` 只对宿主机 loopback 开放，公网走不到 → 全部经 nginx 443。

## 5. 验证链路

```bash
# 宿主机直连容器
curl -s http://127.0.0.1:8080/actuator/health

# 走 nginx
curl -s https://api.实际域名.com/api/v1/health
curl -sI https://api.实际域名.com/  # 看 HSTS / 安全 headers

# WebSocket 握手
curl -sI -H "Upgrade: websocket" -H "Connection: Upgrade" https://api.实际域名.com/ws
```

## 6. 安全组放行

EC2 Security Group：
- 22 / TCP / 你的办公 IP 段
- 80 / TCP / 0.0.0.0/0  （certbot HTTP-01）
- 443 / TCP / 0.0.0.0/0
- **不要** 放行 8080 → 已绑 loopback，外网访问不到

## 7. 常见 gotcha

| 问题 | 排查 |
|---|---|
| `502 Bad Gateway` | `docker ps` 看 backend 是否 healthy；`curl 127.0.0.1:8080/actuator/health` |
| 证书申请失败 | DNS A 记录是否解析到 EC2 弹性 IP；安全组 80 口是否放开 |
| WebSocket 断开快 | nginx `proxy_read_timeout 3600s` 已设，检查 ALB / CloudFront 中间层 idle timeout |
| `403 forbidden` | nginx `actuator` 段只允许 127.0.0.1 + 172.16/12，从公网调 actuator 是预期被拒 |
| `nginx: [emerg] limit_req_zone` 报错 | 上面第 2 步的 `velvet-upstream.conf` 没拆出来；limit_req_zone 必须在 http{} 顶层 |

## 8. 回滚

```bash
sudo rm /etc/nginx/sites-enabled/velvet.conf
sudo systemctl reload nginx
docker compose -f docker-compose.aws.yml down
```
