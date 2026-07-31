# 变更日志

## 数据库迁移：SQLite → MySQL

### 变更内容

1. **依赖变更**
   - 移除：`sqlite-jdbc`
   - 新增：`mysql-connector-j` (8.2.0)

2. **配置变更**
   - 数据库驱动：`org.sqlite.JDBC` → `com.mysql.cj.jdbc.Driver`
   - 连接 URL：SQLite 文件路径 → MySQL 连接字符串
   - 新增连接池配置（HikariCP）

3. **数据库脚本**
   - 数据类型：TEXT → VARCHAR/DATETIME
   - 字符集：utf8mb4
   - 存储引擎：InnoDB
   - 新增索引优化

4. **初始化逻辑**
   - 移除 SQLite 特定的目录创建逻辑
   - 更新表存在性检查（使用 information_schema）

### 配置说明

#### 开发环境
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mobile_standalone?...
    username: root
    password: root
```

#### 生产环境
使用环境变量：
- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USERNAME`
- `DB_PASSWORD`

### 数据库要求

- MySQL 8.0 或更高版本
- 字符集：utf8mb4
- 需要手动创建数据库（或修改连接 URL 中的数据库名）

### 迁移步骤

1. 安装并启动 MySQL
2. 创建数据库（可选，会自动创建）
3. 修改 `application.yml` 中的连接信息
4. 启动应用，自动创建表结构
