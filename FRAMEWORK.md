# 后端框架架构文档

## 📋 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| **框架** | Spring Boot | 3.2.0 |
| **ORM** | MyBatis | 3.0.3 |
| **数据库** | SQLite | 3.44.1.0 |
| **语言** | Java | 17 |
| **构建工具** | Maven | - |
| **工具库** | Lombok | - |
| **测试框架** | TestNG | 7.8.0 |

---

## 🏗️ 项目结构

```
backend/
├── pom.xml                                    # Maven 依赖配置
├── README.md                                  # 项目说明
├── FRAMEWORK.md                               # 框架文档（本文件）
│
└── src/main/
    ├── java/com/mobile/standalone/
    │   ├── BackendApplication.java            # 主启动类
    │   │
    │   ├── config/                            # 配置类
    │   │   ├── CorsConfig.java               # CORS 跨域配置
    │   │   ├── MyBatisConfig.java            # MyBatis 配置
    │   │   └── DatabaseInitializer.java     # 数据库初始化器
    │   │
    │   ├── controller/                        # REST API 控制器层
    │   │   ├── DeviceController.java         # 设备管理 API
    │   │   ├── TaskController.java           # 任务管理 API
    │   │   ├── ReportController.java         # 报告管理 API
    │   │   └── HealthController.java         # 健康检查 API
    │   │
    │   ├── service/                           # 业务逻辑层
    │   │   ├── DeviceService.java            # 设备服务
    │   │   └── TaskService.java              # 任务服务
    │   │
    │   ├── mapper/                            # MyBatis Mapper 接口
    │   │   ├── DeviceMapper.java             # 设备数据访问
    │   │   ├── TaskMapper.java               # 任务数据访问
    │   │   └── TaskExecutionMapper.java      # 任务执行数据访问
    │   │
    │   └── entity/                            # 实体类（POJO）
    │       ├── Device.java                   # 设备实体
    │       ├── Task.java                     # 任务实体
    │       └── TaskExecution.java           # 任务执行实体
    │
    └── resources/
        ├── application.yml                   # 应用配置文件
        │
        ├── mapper/                            # MyBatis Mapper XML
        │   ├── DeviceMapper.xml              # 设备 SQL 映射
        │   ├── TaskMapper.xml                # 任务 SQL 映射
        │   └── TaskExecutionMapper.xml       # 任务执行 SQL 映射
        │
        └── db/
            └── schema.sql                    # 数据库表结构脚本
```

---

## 🎯 架构设计

### 分层架构

```
┌─────────────────────────────────────────┐
│     Controller Layer (控制器层)          │
│  - 接收 HTTP 请求                        │
│  - 参数验证                              │
│  - 调用 Service 层                      │
│  - 返回 JSON 响应                        │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│      Service Layer (业务逻辑层)         │
│  - 业务逻辑处理                         │
│  - 事务管理 (@Transactional)           │
│  - 调用 Mapper 层                       │
│  - 异常处理                             │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│      Mapper Layer (数据访问层)          │
│  - MyBatis Mapper 接口                  │
│  - SQL 映射 (XML)                       │
│  - 数据库操作                           │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│      Entity Layer (实体层)              │
│  - POJO 对象                            │
│  - 数据模型                             │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│      Database (SQLite)                  │
│  - 本地文件数据库                        │
│  - 自动初始化表结构                      │
└─────────────────────────────────────────┘
```

---

## 📦 核心组件

### 1. Controller 层

**职责：**
- 接收 HTTP 请求
- 参数验证
- 调用 Service
- 返回响应

**示例：**
```java
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService taskService;
    
    @GetMapping
    public ResponseEntity<List<Task>> getAllTasks() {
        return ResponseEntity.ok(taskService.getAllTasks());
    }
}
```

### 2. Service 层

**职责：**
- 业务逻辑处理
- 事务管理
- 调用 Mapper
- 异常处理

**示例：**
```java
@Service
@RequiredArgsConstructor
@Transactional
public class TaskService {
    private final TaskMapper taskMapper;
    
    public Task createTask(Task task) {
        task.setId(UUID.randomUUID().toString());
        taskMapper.insert(task);
        return task;
    }
}
```

### 3. Mapper 层

**接口定义：**
```java
@Mapper
public interface TaskMapper {
    List<Task> findAll();
    Task findById(@Param("id") String id);
    int insert(Task task);
    int update(Task task);
    int deleteById(@Param("id") String id);
}
```

**XML 映射：**
```xml
<mapper namespace="com.mobile.standalone.mapper.TaskMapper">
    <select id="findAll" resultMap="TaskResultMap">
        SELECT * FROM tasks ORDER BY created_at DESC
    </select>
    
    <insert id="insert">
        INSERT INTO tasks (id, name, ...) 
        VALUES (#{id}, #{name}, ...)
    </insert>
</mapper>
```

### 4. Entity 层

**特点：**
- 纯 POJO（无 JPA 注解）
- 使用 Lombok `@Data` 简化代码
- 包含枚举类型

**示例：**
```java
@Data
public class Task {
    private String id;
    private String name;
    private TaskStatus status;
    private LocalDateTime createdAt;
    
    public enum TaskStatus {
        PENDING, RUNNING, COMPLETED, FAILED, STOPPED
    }
}
```

---

## ⚙️ 配置说明

### application.yml

```yaml
server:
  port: 3000
  servlet:
    context-path: /api

spring:
  datasource:
    driver-class-name: org.sqlite.JDBC
    url: jdbc:sqlite:${user.home}/.mobile-standalone/data/app.db

mybatis:
  mapper-locations: classpath:mapper/*.xml
  type-aliases-package: com.mobile.standalone.entity
  configuration:
    map-underscore-to-camel-case: true
```

### MyBatis 配置

```java
@Configuration
@MapperScan("com.mobile.standalone.mapper")
public class MyBatisConfig {
}
```

---

## 🔄 数据流

### 请求处理流程

```
HTTP Request (POST /api/tasks)
    ↓
TaskController.createTask()
    ↓
TaskService.createTask()
    ↓
TaskMapper.insert()
    ↓
TaskMapper.xml <insert> SQL
    ↓
SQLite Database
    ↓
Response (JSON)
```

### 数据库初始化流程

```
应用启动
    ↓
DatabaseInitializer.run()
    ↓
检查表是否存在
    ↓
执行 schema.sql
    ↓
创建表结构
```

---

## 📊 数据库设计

### 表结构

#### devices 表
- `id` (TEXT PRIMARY KEY)
- `name` (TEXT NOT NULL)
- `platform` (TEXT NOT NULL)
- `version` (TEXT)
- `status` (TEXT DEFAULT 'DISCONNECTED')
- `last_seen` (TEXT)
- `config` (TEXT)

#### tasks 表
- `id` (TEXT PRIMARY KEY)
- `name` (TEXT NOT NULL)
- `description` (TEXT)
- `project_path` (TEXT NOT NULL)
- `script_path` (TEXT NOT NULL)
- `platform` (TEXT NOT NULL)
- `device_id` (TEXT)
- `status` (TEXT DEFAULT 'PENDING')
- `created_at` (TEXT NOT NULL)
- `updated_at` (TEXT NOT NULL)
- `config` (TEXT)

#### task_executions 表
- `id` (TEXT PRIMARY KEY)
- `task_id` (TEXT NOT NULL)
- `status` (TEXT NOT NULL)
- `start_time` (TEXT)
- `end_time` (TEXT)
- `report_path` (TEXT)
- `error_message` (TEXT)

---

## 🚀 启动流程

1. **加载配置** → 读取 `application.yml`
2. **初始化数据源** → 连接 SQLite 数据库
3. **执行数据库初始化** → 运行 `schema.sql` 创建表
4. **扫描 Mapper** → 加载 MyBatis Mapper
5. **启动 Spring 容器** → 加载所有 Bean
6. **启动 Tomcat** → 监听 3000 端口
7. **启动定时任务** → 设备扫描任务

---

## 🔧 核心特性

### 1. MyBatis 优势
- ✅ 灵活的 SQL 控制
- ✅ 直接 SQL 操作，性能更好
- ✅ SQL 可见，易于调试
- ✅ 支持复杂查询

### 2. 自动初始化
- ✅ 首次启动自动创建数据库目录
- ✅ 自动执行 `schema.sql` 创建表结构
- ✅ 无需手动建表

### 3. 事务管理
- ✅ Spring `@Transactional` 支持
- ✅ 自动回滚机制

### 4. 跨域支持
- ✅ CORS 配置
- ✅ 支持前端跨域请求

---

## 📝 开发规范

### 命名规范
- **Controller**: `XxxController`
- **Service**: `XxxService`
- **Mapper**: `XxxMapper`
- **Entity**: `Xxx` (单数形式)

### 注解使用
- `@RestController`: REST API 控制器
- `@Service`: 业务逻辑层
- `@Mapper`: MyBatis Mapper 接口
- `@Transactional`: 事务管理
- `@RequiredArgsConstructor`: Lombok 依赖注入

### 代码组织
- Controller 只负责接收请求和返回响应
- Service 处理业务逻辑
- Mapper 只负责数据访问
- Entity 是纯数据对象

---

## 🎓 技术特点

1. **轻量级**: SQLite 本地数据库，无需额外服务
2. **RESTful**: 标准 REST API 设计
3. **异步处理**: 任务执行采用线程池
4. **自动初始化**: 数据库表自动创建
5. **跨域支持**: 完整的 CORS 配置
6. **SQL 可控**: MyBatis 提供灵活的 SQL 控制

---

## 📚 扩展建议

1. **分页支持**: 添加 MyBatis PageHelper
2. **缓存**: 集成 Redis 或本地缓存
3. **日志**: 完善日志记录和监控
4. **异常处理**: 统一异常处理机制
5. **API 文档**: 集成 Swagger/OpenAPI
6. **单元测试**: 完善测试覆盖
