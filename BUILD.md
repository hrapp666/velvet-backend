# Velvet · 打包 / 发版手册（提包组 SOP）

> 写给「拿到代码就能出包」的同学。命令全部可复制粘贴。
> 版本节点：JDK 21 · Maven 3.8+ · Flutter 3.41.6（Dart 3.11.4）· Android compileSdk 由 Flutter 决定（当前 35）

---

## 0 · 仓库结构

```
/root/velvet/
├── velvet-backend/        # Spring Boot 3.5（Java 21）→ 出 fat-jar / Docker 镜像
├── velvet-flutter/        # Flutter 主 App → 出 APK / IPA / Web
├── h5-demo/               # 营销 / 直播 H5 → 部署到 agent.ylctkx9s.work
├── docker-compose.yml     # 全栈编排（postgres + redis + minio + backend + nginx）
├── Makefile               # 一键命令（见 §5）
└── BUILD.md               # 你正在看
```

仓库地址：

| 模块 | GitHub | GitLab |
|---|---|---|
| velvet-backend | https://github.com/hrapp666/velvet-backend.git | _未建_（提包组不需要管） |
| velvet-flutter | https://github.com/hrapp666/velvet-flutter.git | https://gitlab.qmpexevcqy.work/flutter_apps/velvet-flutter.git |

---

## 1 · 环境准备（一次性）

| 工具 | 最低版本 | 验证 |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.8 | `mvn -v` |
| Flutter | 3.41.6 | `flutter --version` |
| Android SDK | API 35 | `flutter doctor` |
| Xcode（仅 iOS） | 15+ | `xcodebuild -version` |
| Docker | 24+ | `docker --version` |

```bash
# Linux 一键校验
java -version && mvn -v && flutter --version && docker --version
```

---

## 2 · 后端 Spring Boot 打包

### 2.1 出 fat-jar（本机）

```bash
cd /root/velvet/velvet-backend
mvn -B -DskipTests clean package
ls -lh target/*.jar
```

**产物**：`target/velvet-backend-<version>.jar`（约 80 MB · 含所有依赖）

启动测试：
```bash
java -jar target/velvet-backend-*.jar --spring.profiles.active=dev
```

### 2.2 出 Docker 镜像（VPS 上）

`Dockerfile` 是多阶段构建（maven:3.9-eclipse-temurin-21-alpine → eclipse-temurin:21-jre-alpine）。

```bash
cd /root/velvet
docker compose build backend
docker compose up -d backend
docker compose logs -f backend
```

**镜像名**：`velvet-backend:latest` · **运行容器名**：`velvet-backend`
**端口**：容器 8080 → 宿主 127.0.0.1:8181（仅本机，公网走 Cloudflare Tunnel）

### 2.3 健康检查

```bash
curl -s http://127.0.0.1:8181/api/v1/health
# 期望：{"status":"UP","db":"mysql/postgres","redis":"UP"}
```

---

## 3 · Flutter 主 App 打包

### 3.1 拉依赖

```bash
cd /root/velvet/velvet-flutter
flutter pub get
```

### 3.2 Android APK（release）

```bash
flutter build apk --release
```

**产物**：`build/app/outputs/flutter-apk/app-release.apk`
**applicationId**：`com.hrapp.velvet`

> ⚠️ **打 APK 推荐去 VPS 跑** — 本机吃 CPU 严重 · 用 `make flutter-apk`（见 §5）一键远程打 + 拉回。

### 3.3 Android App Bundle（上 Google Play）

```bash
flutter build appbundle --release
# 产物：build/app/outputs/bundle/release/app-release.aab
```

### 3.4 iOS IPA

```bash
cd ios && pod install && cd ..
flutter build ipa --release
# 产物：build/ios/ipa/*.ipa
# 上传：用 Transporter 或 fastlane
```

> iOS 必须在 macOS 上打。证书 / Provisioning Profile 在主人 Apple ID 下。

### 3.5 Web

```bash
flutter build web --release
# 产物：build/web/（静态站点 · 直接 rsync 到 nginx 即可）
```

### 3.6 清理

```bash
flutter clean
```

---

## 4 · H5 营销页打包 / 部署

H5 是纯静态文件 · 无构建步骤 · 直接 rsync 到 VPS。

```bash
rsync -azh --delete \
  -e "ssh -i ~/.ssh/velvet_vps" \
  /root/velvet/h5-demo/ \
  root@145.223.88.222:/root/velvet/h5-demo/

ssh -i ~/.ssh/velvet_vps root@145.223.88.222 "nginx -t && systemctl reload nginx"
```

**线上地址**：https://agent.ylctkx9s.work
**Nginx 配置**：`/etc/nginx/sites-enabled/velvet.conf`（root → `/root/velvet/h5-demo`）

---

## 5 · 一键命令（推荐）

`/root/velvet/Makefile` 已封装所有命令：

```bash
cd /root/velvet
make help                  # 看清单
make backend-jar           # 本机出 fat-jar
make backend-deploy        # 推 VPS + docker 重启
make backend-logs          # tail 容器日志
make flutter-apk           # VPS 远程打 release APK + 拉回
make flutter-web           # 本机 build web
make h5-deploy             # rsync H5 + nginx reload
make all-deploy            # 后端 + H5 一起发版
```

---

## 6 · 产物清单（提包组关心的就这几个）

| 平台 | 产物 | 路径 |
|---|---|---|
| 后端 jar | `velvet-backend-*.jar` | `velvet-backend/target/` |
| 后端 docker 镜像 | `velvet-backend:latest` | VPS 本地 docker registry |
| Android APK | `app-release.apk` | `velvet-flutter/build/app/outputs/flutter-apk/` |
| Android AAB | `app-release.aab` | `velvet-flutter/build/app/outputs/bundle/release/` |
| iOS IPA | `*.ipa` | `velvet-flutter/build/ios/ipa/` |
| Flutter Web | `index.html + assets/` | `velvet-flutter/build/web/` |
| H5 | 静态文件 | `h5-demo/` |

---

## 7 · 常见问题

### 7.1 mvn 拉依赖慢
配国内镜像 — 编辑 `~/.m2/settings.xml` 加阿里云 / 腾讯云镜像。

### 7.2 Flutter 报 `running as root` 警告
Linux VPS 上跑必现 · 不影响打包 · 无视。

### 7.3 docker compose build 失败 `no such service`
服务名是 `backend` · **不是** `velvet-backend`。先 `docker compose config --services` 确认。

### 7.4 APK 包名要改怎么办
改 `velvet-flutter/android/app/build.gradle.kts` 里的 `applicationId`。
版本号在 `pubspec.yaml` 里改 `version: 0.1.0+1` · 加号前是 versionName · 后是 versionCode。

### 7.5 后端 401 / 403 怎么区分
未带 token 或 token 失效 → **401 Unauthorized**（前端拿到要触发刷新或跳登录）
带了合法 token 但权限不够 → **403 Forbidden**

### 7.6 改了代码怎么知道部署上去没
```bash
# 后端
curl https://api.ylctkx9s.work/api/v1/health
docker compose -f /root/velvet/docker-compose.yml logs --tail=50 backend | grep "Started VelvetBackendApplication"

# H5
curl -s -I https://agent.ylctkx9s.work/app.js | grep -i last-modified
md5sum h5-demo/app.js  # 本机 md5 应等于 VPS 上的
```

---

## 8 · 发版前检查清单

- [ ] 后端 `mvn test` 全绿
- [ ] Flutter `flutter analyze` 无 error
- [ ] H5 在浏览器手验过登录 + 主流程
- [ ] 数据库 Flyway migration 顺序正确（`V1`–`V13` 不能跳号）
- [ ] `application.yml` 里的密钥 / 数据库连接没硬编码
- [ ] APK 装到真机能进首页 + 登录成功
- [ ] CHANGELOG / git tag 打了

---

## 9 · 联系人

- **后端代码**：velvet-backend 仓库 README
- **Flutter 代码**：velvet-flutter 仓库 README
- **生产事故**：`docs/RUNBOOK.md`
- **VPS 访问**：找主人要 `~/.ssh/velvet_vps` 私钥 + IP `145.223.88.222`
