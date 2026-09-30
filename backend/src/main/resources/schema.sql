-- ============================================================================
-- EduPlatform 数据库 DDL（H2 内存/文件库 + MySQL 兼容模式）
-- spring.sql.init.mode=always，每次启动都会执行，因此必须幂等（IF NOT EXISTS）。
-- 注意：H2/MySQL 中 user 是保留字，故用户表命名为 sys_user。
-- ============================================================================

CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password VARCHAR(120) NOT NULL,
  real_name VARCHAR(64) NOT NULL,
  role VARCHAR(16) NOT NULL,
  student_id VARCHAR(32),
  teacher_id VARCHAR(32),
  major VARCHAR(64),
  title VARCHAR(64),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS course (
  course_id VARCHAR(32) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  credit INT NOT NULL DEFAULT 0,
  description VARCHAR(1000),
  teacher_id VARCHAR(32),
  capacity INT NOT NULL DEFAULT 0,
  selected INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS course_selection (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  student_id VARCHAR(32) NOT NULL,
  course_id VARCHAR(32) NOT NULL,
  grade DOUBLE,
  select_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_selection UNIQUE (student_id, course_id)
);

CREATE TABLE IF NOT EXISTS assignment (
  assign_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  course_id VARCHAR(32) NOT NULL,
  title VARCHAR(200) NOT NULL,
  description VARCHAR(2000),
  deadline TIMESTAMP,
  status INT NOT NULL DEFAULT 1,
  publish_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS submission (
  sub_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  assign_id BIGINT NOT NULL,
  student_id VARCHAR(32) NOT NULL,
  content VARCHAR(4000),
  submit_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  grade DOUBLE,
  feedback VARCHAR(1000),
  CONSTRAINT uk_submission UNIQUE (assign_id, student_id)
);

CREATE TABLE IF NOT EXISTS knowledge_node (
  node_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  course_id VARCHAR(32) NOT NULL,
  parent_id BIGINT,
  node_name VARCHAR(200) NOT NULL,
  content VARCHAR(2000),
  order_num INT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_sub_assign_student ON submission(assign_id, student_id);
CREATE INDEX IF NOT EXISTS idx_sel_course ON course_selection(course_id);
CREATE INDEX IF NOT EXISTS idx_assign_course ON assignment(course_id, status);
