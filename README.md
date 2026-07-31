# Mobile Standalone Backend

基于 Spring Boot + MyBatis 的后端服务，提供 RESTful API 接口。

## 技术栈

- **框架**: Spring Boot 3.2.0
- **ORM**: MyBatis 3.0.3
- **数据库**: MySQL 8.0+
- **Java 版本**: 17

## 项目结构

```
backend/
├── src/main/java/com/mobile/standalone/
│   ├── BackendApplication.java      # 主启动类
│   ├── config/                      # 配置类
│   │   ├── CorsConfig.java         # CORS 配置
│   │   ├── MyBatisConfig.java      # MyBatis 配置
│   │   └── DatabaseInitializer.java # 数据库初始化
│   ├── controller/                  # REST API 控制器
│   ├── service/                     # 业务逻辑层
│   ├── mapper/                      # MyBatis Mapper 接口
│   └── entity/                      # 实体类
└── src/main/resources/
    ├── application.yml              # 应用配置
    ├── mapper/                      # MyBatis Mapper XML
    └── db/                          # 数据库脚本
        └── schema.sql               # 数据库表结构
```

## 功能模块

1. **设备管理** (`/api/devices`)
   - 获取设备列表
   - 连接/断开设备
   - 设备状态监控

2. **任务管理** (`/api/tasks`)
   - 创建/更新/删除任务
   - 运行/停止任务
   - 任务状态查询

3. **报告管理** (`/api/reports`)
   - 获取报告列表
   - 查看报告详情

## 启动方式

### 使用 Maven

```bash
cd backend
mvn spring-boot:run
```

### 使用 IDE

直接运行 `BackendApplication.java` 的 main 方法。

## 数据库

使用 MySQL 数据库，需要先安装并启动 MySQL 服务。

### 数据库配置

1. **安装 MySQL** (8.0 或更高版本)
2. **创建数据库**（可选，会自动创建）:
   ```sql
   CREATE DATABASE mobile_standalone DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```
3. **配置连接信息**：修改 `application.yml` 中的数据库连接信息：
   ```yaml
   spring:
     datasource:
       url: jdbc:mysql://localhost:3306/mobile_standalone?...
       username: root
       password: your_password
   ```

### 数据库初始化

项目启动时会自动执行 `schema.sql` 创建表结构：
- `devices` - 设备表
- `tasks` - 任务表
- `task_executions` - 任务执行历史表
- `settings` - 配置表

### 环境配置

- **开发环境**: 使用 `application.yml` 或 `application-dev.yml`
- **生产环境**: 使用 `application-prod.yml`，可通过环境变量配置：
  - `DB_HOST`: 数据库主机
  - `DB_PORT`: 数据库端口
  - `DB_NAME`: 数据库名称
  - `DB_USERNAME`: 数据库用户名
  - `DB_PASSWORD`: 数据库密码

## API 文档

服务启动后，API 基础路径为：`http://localhost:3000/api`

### 设备管理

- `GET /api/devices` - 获取所有设备
- `GET /api/devices/{id}` - 获取设备详情
- `POST /api/devices/{id}/connect` - 连接设备
- `POST /api/devices/{id}/disconnect` - 断开设备

### 任务管理

- `GET /api/tasks` - 获取所有任务
- `GET /api/tasks/{id}` - 获取任务详情
- `POST /api/tasks` - 创建任务
- `PUT /api/tasks/{id}` - 更新任务
- `DELETE /api/tasks/{id}` - 删除任务
- `POST /api/tasks/{id}/run` - 运行任务
- `POST /api/tasks/{id}/stop` - 停止任务

### 报告管理

- `GET /api/reports` - 获取所有报告
- `GET /api/reports/{id}` - 获取报告详情

## 配置说明

配置文件：`src/main/resources/application.yml`

- 服务端口：3000
- API 路径：`/api`
- 数据库路径：`~/.mobile-standalone/data/app.db`
- MyBatis Mapper 位置：`classpath:mapper/*.xml`

## MyBatis 配置

- Mapper 接口扫描：`com.mobile.standalone.mapper`
- Mapper XML 位置：`src/main/resources/mapper/`
- 实体类别名包：`com.mobile.standalone.entity`
- 下划线转驼峰：已启用

## 开发说明

### 添加新的 Mapper

1. 在 `mapper/` 包下创建 Mapper 接口
2. 在 `resources/mapper/` 下创建对应的 XML 文件
3. 在 Service 中注入 Mapper 使用

### 数据库操作

所有数据库操作通过 MyBatis Mapper 进行，支持：
- 注解方式（简单查询）
- XML 方式（复杂查询）
