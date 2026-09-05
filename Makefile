# Weaveora 开发命令（§25 / §28.1）。Windows Git Bash / Linux 通用。
# 使用：make api-compile  /  make api-dev 等。
# JAVA_HOME 指向 JDK21（v1.3 裁定 #18 版本钉扎）。

SHELL := bash
JDK21 ?= /c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot

export JAVA_HOME := $(JDK21)
export PATH := $(JAVA_HOME)/bin:$(PATH)

.PHONY: api-compile api-dev web-dev bootstrap

api-compile:
	cd api && mvn -q compile

api-dev:
	cd api && mvn spring-boot:run

web-dev:
	cd web && pnpm dev

bootstrap:
	@echo "建库（本机 weaveora_dev）：PGPASSWORD=postgres psql -h localhost -U postgres -c 'CREATE DATABASE weaveora_dev;'（幂等，已存在则跳过）"
	@echo "Redis：ssh -N -L 6379:127.0.0.1:6379 root@sysou.com  # 连 VPS db1（v1.4/1.5）"
