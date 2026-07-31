# 从 JPA 迁移到 MyBatis

## 变更内容

### 1. 依赖变更

**移除:**
- `spring-boot-starter-data-jpa`
- `hibernate-community-dialects`

**新增:**
- `mybatis-spring-boot-starter` (3.0.3)

### 2. 配置变更

**application.yml:**
- 移除 JPA/Hibernate 配置
- 新增 MyBatis 配置

### 3. 代码变更

**Repository → Mapper:**
- `DeviceRepository` → `DeviceMapper`
- `TaskRepository` → `TaskMapper`
- `TaskExecutionRepository` → `TaskExecutionMapper`

**实体类:**
- 移除所有 JPA 注解 (`@Entity`, `@Table`, `@Column`, `@Id` 等)
- 保留 Lombok `@Data` 注解

**Service 层:**
- 所有 `repository.save()` → `mapper.insert()` 或 `mapper.update()`
- 所有 `repository.findById().orElse(null)` → `mapper.findById()`
- 所有 `repository.existsById()` → `mapper.existsById()`

### 4. 新增文件

- `mapper/` 包：MyBatis Mapper 接口
- `resources/mapper/` 目录：MyBatis Mapper XML 文件
- `resources/db/schema.sql`：数据库初始化脚本
- `config/DatabaseInitializer.java`：数据库初始化器
- `config/MyBatisConfig.java`：MyBatis 配置类

### 5. 数据库初始化

MyBatis 不会自动建表，需要手动执行 SQL 脚本。项目启动时会自动执行 `schema.sql` 创建表结构。

## 优势

1. **更灵活的 SQL 控制**: 可以编写复杂的 SQL 语句
2. **更好的性能**: 直接 SQL 操作，无 ORM 转换开销
3. **更易调试**: SQL 语句清晰可见
4. **更轻量级**: 不需要 Hibernate 的复杂特性
