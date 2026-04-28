-- =========================================================
-- V3: 给 demo VH 1 的草稿版本 1 种 4 个意图 + 意图智能体配置
-- 依赖 V2 的 vh_version(1)
-- =========================================================

-- 意图智能体 (1:1 with vh_version)
INSERT INTO vh_intent_agent
    (vh_version_id, provider, model_name, credential_id,
     temperature, max_tokens, classifier_prompt, fallback_intent_code)
VALUES
    (1, 'deepseek', 'deepseek-chat', 1,
     0.00, 200, NULL, 'chitchat');

-- 意图定义
INSERT INTO vh_intent
    (vh_version_id, intent_code, intent_name, description, examples, bound_tool_id, sort_order)
VALUES
(1, 'weather_query', '天气查询',
 '用户询问某城市天气、温度、是否下雨、是否需要带伞或外套等天气相关问题',
 JSON_ARRAY('上海现在天气怎样', '明天会下雨吗', '北京多少度', '出门要带伞吗', '今天热不热'),
 1, 10),

(1, 'book_recommendation', '书籍推荐',
 '用户想要小语推荐书籍, 或讨论读书相关话题',
 JSON_ARRAY('推荐本好书', '最近在读什么', '有什么散文推荐', '好看的小说', '看什么书打发时间'),
 NULL, 20),

(1, 'music_share', '音乐分享',
 '用户想要小语分享喜欢的音乐, 或讨论音乐相关话题',
 JSON_ARRAY('推荐首歌', '你喜欢什么音乐', '最近在听什么', '独立音乐有什么好的', '这首歌怎么样'),
 NULL, 30),

(1, 'chitchat', '闲聊',
 '兜底意图: 任何不属于上述具体意图的对话, 包括问候、自我介绍、生活分享、情感表达等',
 JSON_ARRAY('你好', '在干嘛', '今天好累', '周末打算', '我刚下班'),
 NULL, 99);
