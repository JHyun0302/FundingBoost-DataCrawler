## 배포 관련
```shell
sudo mkdir -p /opt/funding-crawler/logs
sudo chown -R funding:funding /opt/funding-crawler
```

```markdown
/opt/funding-crawler/
├─ funding-crawler.war
└─ logs/
    └─ app.log   (애플리케이션 로그)
```

### /etc/systemd/system/funding-crawler.service
```ini
[Unit]
Description=Funding Crawler WAR Service
After=network.target

[Service]
Type=simple

# 서비스 실행 계정 (필요한 계정명으로 변경)
User=funding
Group=funding

WorkingDirectory=/opt/funding-crawler

# 로그 파일 경로: Spring Boot 기준 예시
#   --logging.file.name=/opt/funding-crawler/logs/app.log
ExecStart=/usr/bin/java -jar \
  /opt/funding-crawler/funding-crawler.war \
  --spring.profiles.active=prod \
  --logging.file.name=/opt/funding-crawler/logs/app.log

# 로그 디렉터리 없으면 만들어두기 (root로 실행 시에만 의미 있음)
ExecStartPre=/usr/bin/mkdir -p /opt/funding-crawler/logs
ExecStartPre=/usr/bin/chown -R funding:funding /opt/funding-crawler

Restart=always
RestartSec=10
SuccessExitStatus=143

# 표준출력은 파일로 관리할 거라 journald는 최소화
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

### 적용
```bash
sudo systemctl daemon-reload
sudo systemctl enable funding-crawler
sudo systemctl start funding-crawler

sudo systemctl status funding-crawler
```

---
# 3일 이상 지난 로그 삭제

### /etc/cron.daily/funding-crawler-log-clean
- 매일 1번 수정 시간 기준 3일 초과된 파일들 삭제
```bash
sudo tee /etc/cron.daily/funding-crawler-log-clean >/dev/null << 'EOF'
#!/usr/bin/env bash
LOG_DIR="/opt/funding-crawler/logs"

# 3일 초과된 로그 파일 삭제
# *.log, 압축된 *.log.gz 등 전부 대상
find "${LOG_DIR}" -type f \( -name '*.log' -o -name '*.log.*' -o -name '*.log.gz' \) -mtime +3 -print -delete
EOF

sudo chmod +x /etc/cron.daily/funding-crawler-log-clean
```