-- =========================================================
-- V1: 基础层 + 配置域 + 运行时骨架 (W1~W2 共用)
--     不含记忆/成本/评估表，后续版本追加
-- =========================================================

-- ----- 基础层 -----
CREATE TABLE tenant (
  id           BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  code         VARCHAR(64)  NOT NULL,
  name         VARCHAR(128) NOT NULL,
  status       TINYINT      NOT NULL DEFAULT 1,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tenant_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_user (
  id           BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  tenant_id    BIGINT UNSIGNED NOT NULL,
  username     VARCHAR(64)  NOT NULL,
  email        VARCHAR(128),
  role         VARCHAR(32)  NOT NULL DEFAULT 'MEMBER',
  status       TINYINT      NOT NULL DEFAULT 1,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_tenant_username (tenant_id, username),
  KEY idx_user_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE credential (
  id           BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  provider     VARCHAR(32)  NOT NULL,
  name         VARCHAR(128) NOT NULL,
  secret_ref   VARCHAR(255) NOT NULL,
  base_url     VARCHAR(255),
  status       TINYINT      NOT NULL DEFAULT 1,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cred_provider_name (provider, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tool (
  id           BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  code         VARCHAR(64)  NOT NULL,
  name         VARCHAR(128) NOT NULL,
  description  TEXT,
  type         VARCHAR(16)  NOT NULL,
  input_schema JSON         NOT NULL,
  config       JSON         NOT NULL,
  status       TINYINT      NOT NULL DEFAULT 1,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tool_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----- 配置域 -----
CREATE TABLE virtual_human (
  id                    BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  tenant_id             BIGINT UNSIGNED NOT NULL,
  owner_id              BIGINT UNSIGNED NOT NULL,
  name                  VARCHAR(128) NOT NULL,
  gender                VARCHAR(16),
  hobbies               VARCHAR(512),
  background            TEXT,
  draft_version_id      BIGINT UNSIGNED,
  published_version_id  BIGINT UNSIGNED,
  deleted_at            DATETIME,
  created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_vh_tenant (tenant_id, deleted_at),
  KEY idx_vh_owner  (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vh_version (
  id            BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  vh_id         BIGINT UNSIGNED NOT NULL,
  version_no    INT          NOT NULL,
  status        VARCHAR(16)  NOT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at  DATETIME,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_version_vh_no (vh_id, version_no),
  KEY idx_version_vh_status (vh_id, status),
  CONSTRAINT fk_version_vh FOREIGN KEY (vh_id) REFERENCES virtual_human(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vh_main_model_config (
  id             BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  vh_version_id  BIGINT UNSIGNED NOT NULL,
  provider       VARCHAR(32)  NOT NULL,
  model_name     VARCHAR(64)  NOT NULL,
  credential_id  BIGINT UNSIGNED NOT NULL,
  temperature    DECIMAL(3,2),
  max_tokens     INT,
  top_p          DECIMAL(3,2),
  extra_params   JSON,
  UNIQUE KEY uk_main_model_version (vh_version_id),
  CONSTRAINT fk_mm_version    FOREIGN KEY (vh_version_id) REFERENCES vh_version(id) ON DELETE CASCADE,
  CONSTRAINT fk_mm_credential FOREIGN KEY (credential_id) REFERENCES credential(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vh_persona_prompt (
  id             BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  vh_version_id  BIGINT UNSIGNED NOT NULL,
  system_prompt  MEDIUMTEXT NOT NULL,
  variables      JSON,
  UNIQUE KEY uk_persona_version (vh_version_id),
  CONSTRAINT fk_persona_version FOREIGN KEY (vh_version_id) REFERENCES vh_version(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vh_intent_agent (
  id                    BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  vh_version_id         BIGINT UNSIGNED NOT NULL,
  provider              VARCHAR(32)  NOT NULL,
  model_name            VARCHAR(64)  NOT NULL,
  credential_id         BIGINT UNSIGNED NOT NULL,
  temperature           DECIMAL(3,2),
  max_tokens            INT,
  classifier_prompt     MEDIUMTEXT,
  fallback_intent_code  VARCHAR(64),
  extra_params          JSON,
  UNIQUE KEY uk_intent_agent_version (vh_version_id),
  CONSTRAINT fk_ia_version    FOREIGN KEY (vh_version_id) REFERENCES vh_version(id) ON DELETE CASCADE,
  CONSTRAINT fk_ia_credential FOREIGN KEY (credential_id) REFERENCES credential(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vh_intent (
  id             BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  vh_version_id  BIGINT UNSIGNED NOT NULL,
  intent_code    VARCHAR(64)  NOT NULL,
  intent_name    VARCHAR(128) NOT NULL,
  description    TEXT,
  examples       JSON,
  bound_tool_id  BIGINT UNSIGNED,
  sort_order     INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_intent_version_code (vh_version_id, intent_code),
  KEY idx_intent_version (vh_version_id),
  CONSTRAINT fk_intent_version FOREIGN KEY (vh_version_id) REFERENCES vh_version(id) ON DELETE CASCADE,
  CONSTRAINT fk_intent_tool    FOREIGN KEY (bound_tool_id) REFERENCES tool(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vh_tool_binding (
  id               BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  vh_version_id    BIGINT UNSIGNED NOT NULL,
  tool_id          BIGINT UNSIGNED NOT NULL,
  override_config  JSON,
  UNIQUE KEY uk_binding_version_tool (vh_version_id, tool_id),
  KEY idx_binding_version (vh_version_id),
  KEY idx_binding_tool    (tool_id),
  CONSTRAINT fk_bind_version FOREIGN KEY (vh_version_id) REFERENCES vh_version(id) ON DELETE CASCADE,
  CONSTRAINT fk_bind_tool    FOREIGN KEY (tool_id)       REFERENCES tool(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vh_version_test_result (
  id             BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  vh_version_id  BIGINT UNSIGNED NOT NULL,
  status         VARCHAR(16)  NOT NULL,
  report         JSON,
  tested_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_test_version (vh_version_id),
  CONSTRAINT fk_test_version FOREIGN KEY (vh_version_id) REFERENCES vh_version(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----- 运行时域 -----
CREATE TABLE conversation (
  id              BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  tenant_id       BIGINT UNSIGNED NOT NULL,
  vh_id           BIGINT UNSIGNED NOT NULL,
  vh_version_id   BIGINT UNSIGNED NOT NULL,
  user_id         BIGINT UNSIGNED,
  channel         VARCHAR(16)  NOT NULL,
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_active_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  closed_at       DATETIME,
  KEY idx_conv_tenant_vh (tenant_id, vh_id),
  KEY idx_conv_user (user_id),
  KEY idx_conv_version (vh_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE message (
  id               BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  conversation_id  BIGINT UNSIGNED NOT NULL,
  role             VARCHAR(16)  NOT NULL,
  content          MEDIUMTEXT   NOT NULL,
  token_count      INT,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_msg_conv_time (conversation_id, created_at),
  CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE execution_trace (
  id               BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  conversation_id  BIGINT UNSIGNED NOT NULL,
  message_id       BIGINT UNSIGNED,
  step             VARCHAR(32)  NOT NULL,
  step_order       INT          NOT NULL,
  input            JSON,
  output           JSON,
  duration_ms      INT,
  error_msg        TEXT,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_trace_conv (conversation_id, created_at),
  KEY idx_trace_msg  (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =========================================================
-- 种子数据: 一个租户 / 用户 / DeepSeek 凭据 / 天气工具
-- =========================================================
INSERT INTO tenant (id, code, name) VALUES (1, 'default', '默认租户');

INSERT INTO sys_user (id, tenant_id, username, email, role)
  VALUES (1, 1, 'admin', 'admin@local', 'ADMIN');

INSERT INTO credential (id, provider, name, secret_ref, base_url)
  VALUES (1, 'deepseek', 'deepseek-default', 'env://DEEPSEEK_API_KEY', 'https://api.deepseek.com/v1');

INSERT INTO tool (id, code, name, description, type, input_schema, config) VALUES
(1, 'weather_query', '查询天气',
 '根据城市名查询当前天气 (使用 wttr.in 免 key 接口)',
 'HTTP_API',
 '{"type":"object","properties":{"city":{"type":"string","description":"城市名,如 Beijing"}},"required":["city"]}',
 '{"method":"GET","url":"https://wttr.in/{city}?format=j1","headers":{}}');
