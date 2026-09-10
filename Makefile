# Weaveora 开发命令（§25 / §28.1）。Windows Git Bash / Linux 通用。
# 使用：make api-compile  /  make api-test  /  make api-dev 等。
# JAVA_HOME 指向 JDK21（v1.3 裁定 #18 版本钉扎）。
# 2026-09-11 修正：原路径 …/Eclipse Adoptium/jdk-21.0.12.101-hotspot 在本机已不存在，
#                   实测 JDK21 位于 D:\jdk21\jdk-21.0.2；可用环境变量 JDK21 覆盖。

SHELL := bash
JDK21 ?= /d/jdk21/jdk-21.0.2

# 找不到就报错退出，避免用错 JDK 静默编出旧字节码
ifeq ($(wildcard $(JDK21)/bin/javac),)
$(warning 警告：JDK21 路径不存在 -> $(JDK21)（请用 make JDK21=<路径> 覆盖）)
endif

export JAVA_HOME := $(JDK21)
export PATH := $(JAVA_HOME)/bin:$(PATH)

.PHONY: api-compile api-test api-dev web-dev bootstrap

api-compile:
	cd api && mvn -q compile

api-test:
	cd api && mvn test

api-dev:
	cd api && mvn spring-boot:run

web-dev:
	cd web && npm run dev   # 本机无 pnpm：用 npm

bootstrap:
	@echo "建库（本机 weaveora_dev）：PGPASSWORD=postgres psql -h localhost -U postgres -c 'CREATE DATABASE weaveora_dev;'（幂等，已存在则跳过）"
	@echo "Redis：ssh -N -L 6379:127.0.0.1:6379 root@sysou.com  # 连 VPS db1（v1.4/1.5）"
