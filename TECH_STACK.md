# 后端技术框架文档

## 📋 技术栈总览

### 核心框架
- **Spring Boot**: 3.2.0
- **Java**: 17
- **构建工具**: Maven

### 数据层
- **数据库**: SQLite 3.44.1.0
- **ORM**: Spring Data JPA
- **JPA实现**: Hibernate (社区版 SQLite 方言)

### Web层
- **Spring Web**: RESTful API
- **CORS**: 跨域资源共享配置
- **JSON处理**: Jackson

### 工具库
- **Lombok**: 简化代码（自动生成getter/setter等）
- **TestNG**: 7.8.0 (用于执行测试脚本)
- **Validation**: 数据验证

### 开发工具
- **Spring Boot DevTools**: 热重载
- **Spring Boot Test**: 单元测试

---

## 🏗️ 项目架构

### 分层架构

```
┌─────────────────────────────────────┐
│      Controller Layer (控制器层)     │
│  - DeviceController                 │
│  - TaskController                   │
│  - ReportController                 │
│  - HealthController                 │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│      Service Layer (业务逻辑层)      │
│  - DeviceService                    │
│  - TaskService                      │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│   Repository Layer (数据访问层)      │
│  - DeviceRepository                 │
│  - TaskRepository                   │
│  - TaskExecutionRepository          │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│      Entity Layer (实体层)           │
│  - Device                           │
│  - Task                             │
│  - TaskExecution                    │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│      Database (SQLite)              │
└─────────────────────────────────────┘
```

---

## 📁 项目结构

```
backend/
├── pom.xml                          # Maven 依赖配置
├── src/main/
│   ├── java/com/mobile/standalone/
│   │   ├── BackendApplication.java  # 主启动类
│   │   ├── config/                 # 配置类
│   │   │   └── CorsConfig.java     # CORS 跨域配置
│   │   ├── controller/             # REST API 控制器
│   │   │   ├── DeviceController.java
│   │   │   ├── TaskController.java
│   │   │   ├── ReportController.java
│   │   │   └── HealthController.java
│   │   ├── service/                 # 业务逻辑层
│   │   │   ├── DeviceService.java
│   │   │   └── TaskService.java
│   │   ├── repository/              # 数据访问层
│   │   │   ├── DeviceRepository.java
│   │   │   ├── TaskRepository.java
│   │   │   └── TaskExecutionRepository.java
│   │   └── entity/                  # 实体类
│   │       ├── Device.java
│   │       ├── Task.java
│   │       └── TaskExecution.java
│   └── resources/
│       └── application.yml          # 应用配置文件
└── README.md
```

---

## 🔧 核心依赖详解

### 1. Spring Boot Web
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```
- 提供 RESTful API 支持
- 内置 Tomcat 服务器
- JSON 序列化/反序列化

### 2. Spring Data JPA
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
```
- 简化数据库操作
- 自动生成 Repository 实现
- 支持事务管理

### 3. SQLite JDBC
```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.44.1.0</version>
</dependency>
```
- SQLite 数据库驱动
- 轻量级本地数据库

### 4. Hibernate Community Dialects
```xml
<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-community-dialects</artifactId>
</dependency>
```
- SQLite 方言支持
- 自动建表/更新表结构

### 5. Lombok
```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
</dependency>
```
- `@Data`: 自动生成 getter/setter
- `@RequiredArgsConstructor`: 构造函数注入
- `@Slf4j`: 日志支持

### 6. TestNG
```xml
<dependency>
    <groupId>org.testng</groupId>
    <artifactId>testng</artifactId>
    <version>7.8.0</version>
</dependency>
```
- 用于执行自动化测试脚本
- 支持测试任务管理

---

## ⚙️ 配置说明

### application.yml 配置

```yaml
server:
  port: 3080                    # 服务端口
  servlet:
    context-path: /api          # API 基础路径

spring:
  datasource:
    driver-class-name: org.sqlite.JDBC
    url: jdbc:sqlite:${user.home}/.mobile-standalone/data/app.db
  
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    hibernate:
      ddl-auto: update          # 自动更新表结构
```

### 关键配置项

- **端口**: 3000
- **API路径**: `/api`
- **数据库**: SQLite (文件路径: `~/.mobile-standalone/data/app.db`)
- **JPA**: 自动建表/更新表结构

---

## 🎯 核心功能模块

### 1. 设备管理 (DeviceService)
- 设备列表查询
- 设备连接/断开
- 定时扫描设备（每3秒）

### 2. 任务管理 (TaskService)
- 任务 CRUD 操作
- 异步任务执行
- 任务状态管理
- 进程管理（启动/停止）

### 3. 报告管理 (ReportController)
- 报告列表查询
- 报告详情获取
- 文件系统操作

---

## 🔄 数据流

### 请求处理流程

```
HTTP Request
    ↓
Controller (接收请求)
    ↓
Service (业务逻辑处理)
    ↓
Repository (数据访问)
    ↓
Entity (实体映射)
    ↓
Database (SQLite)
    ↓
Response (JSON)
```

### 任务执行流程

```
POST /api/tasks/{id}/run
    ↓
TaskService.runTask()
    ↓
更新任务状态为 RUNNING
    ↓
创建 TaskExecution 记录
    ↓
异步执行 (ExecutorService)
    ↓
ProcessBuilder 启动进程
    ↓
监控进程状态
    ↓
更新任务状态 (COMPLETED/FAILED)
```

---

## 📊 数据库设计

### 表结构

#### 1. devices 表
- `id` (主键)
- `name` (设备名称)
- `platform` (平台: ANDROID/IOS/HARMONYOS)
- `version` (版本)
- `status` (状态: CONNECTED/DISCONNECTED)
- `last_seen` (最后看到时间)
- `config` (配置信息)

#### 2. tasks 表
- `id` (主键)
- `name` (任务名称)
- `description` (描述)
- `project_path` (项目路径)
- `script_path` (脚本路径)
- `platform` (平台)
- `device_id` (设备ID)
- `status` (状态: PENDING/RUNNING/COMPLETED/FAILED/STOPPED)
- `created_at` (创建时间)
- `updated_at` (更新时间)
- `config` (配置信息)

#### 3. task_executions 表
- `id` (主键)
- `task_id` (任务ID, 外键)
- `status` (执行状态)
- `start_time` (开始时间)
- `end_time` (结束时间)
- `report_path` (报告路径)
- `error_message` (错误信息)

---

## 🚀 启动流程

1. **加载配置**: 读取 `application.yml`
2. **初始化数据源**: 连接 SQLite 数据库
3. **JPA 初始化**: 自动创建/更新表结构
4. **启动 Spring 容器**: 加载所有 Bean
5. **启动 Tomcat**: 监听 3000 端口
6. **定时任务启动**: 设备扫描任务

---

## 🔐 安全配置

### CORS 配置
- 允许所有来源 (`*`)
- 允许所有请求头
- 允许所有 HTTP 方法
- 支持凭证传递

---

## 📝 开发规范

### 命名规范
- **Controller**: `XxxController`
- **Service**: `XxxService`
- **Repository**: `XxxRepository`
- **Entity**: `Xxx` (单数形式)

### 注解使用
- `@RestController`: REST API 控制器
- `@Service`: 业务逻辑层
- `@Repository`: 数据访问层
- `@Entity`: JPA 实体
- `@Transactional`: 事务管理
- `@RequiredArgsConstructor`: 依赖注入

---

## 🎓 技术特点

1. **轻量级**: SQLite 本地数据库，无需额外数据库服务
2. **RESTful**: 标准 REST API 设计
3. **异步处理**: 任务执行采用线程池异步处理
4. **自动建表**: JPA 自动管理数据库表结构
5. **热重载**: DevTools 支持代码热重载
6. **跨域支持**: 完整的 CORS 配置

---

## 📚 扩展建议

1. **设备检测**: 集成 ADB/libimobiledevice 实现真实设备检测
2. **任务执行**: 完善 TestNG 测试脚本执行逻辑
3. **报告生成**: 集成 Allure 报告生成
4. **日志系统**: 完善日志记录和监控
5. **异常处理**: 统一异常处理机制
6. **API文档**: 集成 Swagger/OpenAPI
