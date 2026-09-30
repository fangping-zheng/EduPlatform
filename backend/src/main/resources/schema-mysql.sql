-- ============================================================
-- EduPlatform MySQL 建表脚本（生产 / mysql profile）
--
-- 与 H2 版 schema.sql 的区别：MySQL 不支持 CREATE INDEX IF NOT EXISTS，
-- 因此索引直接声明在 CREATE TABLE 内部。
-- 所有语句都是幂等的，可重复执行。
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_user (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  username   VARCHAR(64)  NOT NULL,
  password   VARCHAR(120) NOT NULL COMMENT 'BCrypt 哈希，不存明文',
  real_name  VARCHAR(64)  NOT NULL,
  role       VARCHAR(16)  NOT NULL COMMENT 'student / teacher / admin',
  student_id VARCHAR(32)  NULL,
  teacher_id VARCHAR(32)  NULL,
  major      VARCHAR(64)  NULL,
  title      VARCHAR(64)  NULL,
  created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_username (username),
  KEY idx_user_role (role),
  KEY idx_user_student_id (student_id),
  KEY idx_user_teacher_id (teacher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS course (
  course_id   VARCHAR(32)  PRIMARY KEY,
  name        VARCHAR(128) NOT NULL,
  credit      INT          NOT NULL DEFAULT 0,
  description VARCHAR(1000) NULL,
  teacher_id  VARCHAR(32)  NULL,
  capacity    INT          NOT NULL DEFAULT 0,
  selected    INT          NOT NULL DEFAULT 0 COMMENT '已选人数（冗余计数，选课时在行锁内维护）',
  KEY idx_course_teacher (teacher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS course_selection (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  student_id  VARCHAR(32) NOT NULL,
  course_id   VARCHAR(32) NOT NULL,
  grade       DOUBLE      NULL,
  select_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_selection (student_id, course_id),
  KEY idx_sel_course (course_id),
  KEY idx_sel_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS assignment (
  assign_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
  course_id    VARCHAR(32)   NOT NULL,
  title        VARCHAR(200)  NOT NULL,
  description  VARCHAR(2000) NULL,
  deadline     TIMESTAMP     NULL,
  status       INT           NOT NULL DEFAULT 1 COMMENT '1=已发布 0=已撤回',
  publish_time TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  KEY idx_assign_course (course_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS submission (
  sub_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  assign_id   BIGINT        NOT NULL,
  student_id  VARCHAR(32)   NOT NULL,
  content     VARCHAR(4000) NULL,
  submit_time TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  grade       DOUBLE        NULL,
  feedback    VARCHAR(1000) NULL,
  UNIQUE KEY uk_submission (assign_id, student_id),
  KEY idx_sub_assign_student (assign_id, student_id),
  KEY idx_sub_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS knowledge_node (
  node_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
  course_id  VARCHAR(32)   NOT NULL,
  parent_id  BIGINT        NULL,
  node_name  VARCHAR(200)  NOT NULL,
  content    VARCHAR(2000) NULL,
  order_num  INT           DEFAULT 0,
  KEY idx_node_course (course_id, order_num)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
