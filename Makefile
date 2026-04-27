# ============================================================================
# Velvet · 一键打包 / 部署
# ----------------------------------------------------------------------------
# 用法：在 /root/velvet 下执行 make <target>
# 重 CPU 任务（docker build / APK build）走 VPS · 本机不烧
# ============================================================================

VPS        := root@145.223.88.222
SSH_KEY    := /root/.ssh/velvet_vps
SSH        := ssh -i $(SSH_KEY) -o StrictHostKeyChecking=no
RSYNC      := rsync -azh --delete -e "$(SSH)"

BACKEND    := velvet-backend
FLUTTER    := velvet-flutter
H5         := h5-demo

# ---------------------------------------------------------------------------
.PHONY: help
help:
	@echo "Velvet 打包 / 部署"
	@echo ""
	@echo "后端 (Spring Boot):"
	@echo "  make backend-jar       本机 mvn package（fat-jar 到 target/）"
	@echo "  make backend-deploy    rsync 源码到 VPS → docker compose build → up"
	@echo "  make backend-logs      tail VPS backend 容器日志"
	@echo ""
	@echo "前端 Flutter:"
	@echo "  make flutter-deps      pub get"
	@echo "  make flutter-apk       VPS 远程打 APK（release）→ 拉回本机 build/"
	@echo "  make flutter-web       本机 build web → build/web/"
	@echo "  make flutter-clean     flutter clean"
	@echo ""
	@echo "前端 H5 (agent.ylctkx9s.work):"
	@echo "  make h5-deploy         rsync h5-demo/ 到 VPS → nginx reload"
	@echo ""
	@echo "组合:"
	@echo "  make all-deploy        backend-deploy + h5-deploy"

# ---------------------------------------------------------------------------
# 后端
# ---------------------------------------------------------------------------
.PHONY: backend-jar
backend-jar:
	cd $(BACKEND) && mvn -B -DskipTests clean package
	@ls -lh $(BACKEND)/target/*.jar

.PHONY: backend-deploy
backend-deploy:
	$(RSYNC) --exclude target --exclude .git $(BACKEND)/ $(VPS):/root/velvet/$(BACKEND)/
	$(SSH) $(VPS) 'set -o pipefail; cd /root/velvet && docker compose build backend && docker compose up -d backend'
	$(SSH) $(VPS) 'docker compose -f /root/velvet/docker-compose.yml ps backend'

.PHONY: backend-logs
backend-logs:
	$(SSH) $(VPS) 'docker compose -f /root/velvet/docker-compose.yml logs --tail=200 -f backend'

# ---------------------------------------------------------------------------
# Flutter
# ---------------------------------------------------------------------------
.PHONY: flutter-deps
flutter-deps:
	cd $(FLUTTER) && flutter pub get

.PHONY: flutter-apk
flutter-apk:
	$(RSYNC) --exclude build --exclude .dart_tool --exclude .git $(FLUTTER)/ $(VPS):/root/velvet/$(FLUTTER)/
	$(SSH) $(VPS) 'set -o pipefail; cd /root/velvet/$(FLUTTER) && flutter pub get && flutter build apk --release'
	mkdir -p $(FLUTTER)/build/app/outputs/flutter-apk
	$(RSYNC) $(VPS):/root/velvet/$(FLUTTER)/build/app/outputs/flutter-apk/ $(FLUTTER)/build/app/outputs/flutter-apk/
	@ls -lh $(FLUTTER)/build/app/outputs/flutter-apk/*.apk

.PHONY: flutter-web
flutter-web:
	cd $(FLUTTER) && flutter pub get && flutter build web --release
	@du -sh $(FLUTTER)/build/web

.PHONY: flutter-clean
flutter-clean:
	cd $(FLUTTER) && flutter clean

# ---------------------------------------------------------------------------
# H5
# ---------------------------------------------------------------------------
.PHONY: h5-deploy
h5-deploy:
	$(RSYNC) --exclude '*.log' --exclude screenshots $(H5)/ $(VPS):/root/velvet/$(H5)/
	$(SSH) $(VPS) 'nginx -t && systemctl reload nginx'
	@echo "→ https://agent.ylctkx9s.work"

# ---------------------------------------------------------------------------
# 组合
# ---------------------------------------------------------------------------
.PHONY: all-deploy
all-deploy: backend-deploy h5-deploy
