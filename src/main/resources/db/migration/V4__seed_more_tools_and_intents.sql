-- =========================================================
-- V4: 加 calculator + current_time 两个工具和对应意图
-- =========================================================

INSERT INTO tool (id, code, name, description, type, input_schema, config) VALUES
(2, 'calculator', '数学计算',
 '执行数学表达式求值, 支持加减乘除和括号',
 'BUILTIN',
 JSON_OBJECT('type','object',
             'properties', JSON_OBJECT('expression', JSON_OBJECT('type','string','description','数学表达式')),
             'required', JSON_ARRAY('expression')),
 JSON_OBJECT('bean','calculatorTool','method','calculate')),

(3, 'current_time', '当前时间',
 '获取指定时区的当前时间',
 'BUILTIN',
 JSON_OBJECT('type','object',
             'properties', JSON_OBJECT('timezone', JSON_OBJECT('type','string','description','IANA 时区, 如 Asia/Shanghai'))),
 JSON_OBJECT('bean','timeTool','method','getCurrentTime'));

-- 意图: calculation / time_query
INSERT INTO vh_intent
    (vh_version_id, intent_code, intent_name, description, examples, bound_tool_id, sort_order)
VALUES
(1, 'calculation', '数学计算',
 '用户希望执行数学计算或求解算式',
 JSON_ARRAY('帮我算一下 3 加 5', '12 乘 8 等于多少', '100 块打八折是多少', '128 除以 4'),
 2, 11),

(1, 'time_query', '时间查询',
 '用户询问当前时间, 可能指定时区或地点',
 JSON_ARRAY('现在几点了', '现在什么时候', '今天星期几', '纽约现在几点'),
 3, 12);
