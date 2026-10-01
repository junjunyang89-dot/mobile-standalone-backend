# MySQL 数据库配置指南

## 在家 / 在公司两套库

公司共享库走 **公网直连 `118.25.144.133:13306`**。家里本机库是 `127.0.0.1:3306`。**改表结构时两边都要打同一份 SQL。**

```bash
# 公司库（公网）
./scripts/use-db.sh office
./scripts/run-backend.sh

# 用本机库（只有表结构，没有公司的流程/用例数据）
./scripts/use-db.sh home
# 第一次：本机先装好 MySQL 8，再初始化
./scripts/apply-sql.sh init-home

# 新增/改了 src/main/resources/db/migration_*.sql 之后，两边都执行未跑过的文件
./scripts/apply-sql.sh both
# 只打某一个文件
./scripts/apply-sql.sh both src/main/resources/db/migration_xxx.sql
```

切换后要重启后端才生效。当前连的是哪套：`./scripts/use-db.sh`

IDEA Run Configuration → VM options（**不要**写 `-DsocksProxyHost=`，空值也会进 `SocksSocketImpl` 导致 Connection refused）：

```
-Djava.net.useSystemProxies=false
-Dhttp.nonProxyHosts=localhost|127.*|192.168.*|[::1]|118.25.144.133
-Dhttps.proxyHost=
-Dhttp.proxyHost=
```

环境变量里把 `ALL_PROXY`、`all_proxy`、`SOCKS_PROXY`、`HTTPS_PROXY`、`HTTP_PROXY` 留空。项目里已有运行配置 `.run/BackendApplication.run.xml`。

密码写在本机 `application.yml`（公司库）和可选的 `scripts/db-secrets.local`（家里库），都不会进 git。

家里库只同步**表结构**，不同步公司库里的流程/用例数据。若要把公司数据拷一份到家里，自行 `mysqldump` 再导入本机。

---


## 📋 前置要求

- MySQL 8.0 或更高版本
- MySQL 服务已启动

## 🚀 快速配置

### 方式一：自动创建（推荐）

直接启动应用，会自动创建数据库和表结构。

### 方式二：手动创建

1. **登录 MySQL**
   ```bash
   mysql -u root -p
   ```

2. **创建数据库**
   ```sql
   CREATE DATABASE mobile_standalone DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```

3. **创建用户（可选）**
   ```sql
   CREATE USER 'mobile_user'@'localhost' IDENTIFIED BY 'your_password';
   GRANT ALL PRIVILEGES ON mobile_standalone.* TO 'mobile_user'@'localhost';
   FLUSH PRIVILEGES;
   ```

## ⚙️ 配置数据库连接

### 修改 application.yml

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/mobile_standalone?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: your_password
```

### 使用环境变量（生产环境）

```bash
export DB_HOST=localhost
export DB_PORT=3306
export DB_NAME=mobile_standalone
export DB_USERNAME=root
export DB_PASSWORD=your_password
```

然后使用 `application-prod.yml` 配置。

## 📊 数据库表结构

### devices 表
- 设备信息存储
- 支持索引：status, platform

### tasks 表
- 任务信息存储
- 支持索引：status, platform, device_id, created_at

### task_executions 表
- 任务执行历史
- 外键关联 tasks 表
- 支持索引：task_id, status, start_time

### settings 表
- 系统配置存储

## 🔍 验证连接

启动应用后，检查日志：
```
Database schema initialized successfully
```

或连接 MySQL 查看表：
```sql
USE mobile_standalone;
SHOW TABLES;
```

## ❗ 常见问题

### 1. 连接失败
- 检查 MySQL 服务是否启动
- 检查用户名密码是否正确
- 检查端口是否正确（默认 3306）

### 2. 字符编码问题
- 确保数据库使用 `utf8mb4` 字符集
- 确保连接 URL 包含 `useUnicode=true&characterEncoding=utf8`

### 3. 时区问题
- 连接 URL 中包含 `serverTimezone=Asia/Shanghai`
- 或根据实际情况调整时区

### 4. SSL 连接问题
- 开发环境：`useSSL=false`
- 生产环境：`useSSL=true`（需要配置证书）

## 📝 连接池配置

默认使用 HikariCP 连接池：
- 最大连接数：10
- 最小空闲连接：5
- 连接超时：30秒
- 空闲超时：10分钟
- 最大生命周期：30分钟

可在 `application.yml` 中调整。
