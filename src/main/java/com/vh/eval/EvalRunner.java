package com.vh.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vh.repository.entity.Conversation;
import com.vh.repository.entity.ExecutionTrace;
import com.vh.repository.mapper.ConversationMapper;
import com.vh.repository.mapper.ExecutionTraceMapper;
import com.vh.runtime.chat.ChatService;
import com.vh.runtime.config.VhActiveConfig;
import com.vh.runtime.config.VhConfigLoader;
import com.vh.runtime.memory.EpisodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Golden case eval pipeline (W4.D24).
 *
 * <p>用 {@code spring.profiles.active=eval} 启动时自动跑 20 条 case, 真实打 LLM,
 * 跑完 stdout 打分组报告 + 写 {@code logs/eval-{ts}.json}, 然后 exit.
 *
 * <h3>隔离</h3>
 * 每条 case 占用一个独立 userId (起点 {@link #USER_ID_BASE}, 单调递增),
 * 同一 case 内按 {@code convKey} 切多个 conversation, 跨 case 完全隔离, 不污染 demo 数据.
 *
 * <h3>启动</h3>
 * <pre>./mvnw spring-boot:run -Dspring-boot.run.profiles=eval</pre>
 *
 * <h3>YAML schema</h3> 见 {@link EvalCase} 与 {@code resources/eval/golden-cases.yaml}.
 */
@Slf4j
@Component
@Profile("eval")
@RequiredArgsConstructor
public class EvalRunner implements CommandLineRunner {

    /** 隔离起点, 远高于 demo seed (sys_user.id=1) 的范围, 避免误碰. */
    private static final long USER_ID_BASE = 900_000L;
    /** demo VH id, eval 全部跑在小语身上. */
    private static final long DEFAULT_VH_ID = 1L;

    private final ChatService chatService;
    private final VhConfigLoader vhConfigLoader;
    private final EpisodeService episodeService;
    private final ConversationMapper conversationMapper;
    private final ExecutionTraceMapper executionTraceMapper;
    private final EvalAssertionEngine assertionEngine;
    private final ConfigurableApplicationContext applicationContext;
    private final JdbcTemplate jdbcTemplate;

    @Value("${vh.eval.casesFile:eval/golden-cases.yaml}")
    private String casesFile;
    @Value("${vh.eval.reportDir:logs}")
    private String reportDir;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void run(String... args) {
        log.info("=== EvalRunner starting (profile=eval) ===");
        int exitCode = 0;
        try {
            List<EvalCase> cases = loadCases();
            log.info("Loaded {} cases from {}", cases.size(), casesFile);

            EvalReport report = new EvalReport();
            report.setStartedAt(LocalDateTime.now());
            report.setTotalCases(cases.size());

            long t0 = System.currentTimeMillis();
            for (int i = 0; i < cases.size(); i++) {
                EvalCase c = cases.get(i);
                long userId = USER_ID_BASE + i;
                ensureSysUser(userId, c.getId());
                EvalReport.CaseResult result = runCase(c, userId);
                report.getCases().add(result);
                if (result.isPassed()) report.setPassed(report.getPassed() + 1);
                else report.setFailed(report.getFailed() + 1);

                String groupKey = c.getGroup() == null ? "(none)" : c.getGroup();
                EvalReport.GroupSummary g = report.getByGroup().computeIfAbsent(
                        groupKey, k -> new EvalReport.GroupSummary(0, 0));
                g.setTotal(g.getTotal() + 1);
                if (result.isPassed()) g.setPassed(g.getPassed() + 1);

                printCaseLine(i + 1, cases.size(), c, result);
            }
            report.setFinishedAt(LocalDateTime.now());
            report.setDurationMs(System.currentTimeMillis() - t0);

            printSummary(report);
            Path out = writeJsonReport(report);
            log.info("Eval report written to {}", out);

            exitCode = report.getFailed() == 0 ? 0 : 1;
        } catch (Exception e) {
            log.error("Eval run aborted", e);
            exitCode = 2;
        } finally {
            log.info("=== EvalRunner finished, exitCode={} ===", exitCode);
            final int code = exitCode;
            System.exit(org.springframework.boot.SpringApplication.exit(applicationContext, () -> code));
        }
    }

    private List<EvalCase> loadCases() throws Exception {
        try (InputStream in = new ClassPathResource(casesFile).getInputStream()) {
            return yamlMapper.readValue(in, new TypeReference<List<EvalCase>>() {});
        }
    }

    private EvalReport.CaseResult runCase(EvalCase c, long userId) {
        EvalReport.CaseResult result = new EvalReport.CaseResult();
        result.setId(c.getId());
        result.setGroup(c.getGroup());
        result.setDesc(c.getDesc());
        result.setUserId(userId);

        long start = System.currentTimeMillis();
        Map<String, Long> convByKey = new HashMap<>();
        VhActiveConfig vhConfig = vhConfigLoader.load(DEFAULT_VH_ID, VhConfigLoader.Channel.DRAFT);

        try {
            // ---- setup ----
            if (c.getSetup() != null) {
                for (EvalCase.EvalSetup s : c.getSetup()) {
                    runSetup(c, s, userId, vhConfig, convByKey);
                }
            }

            // ---- target ----
            EvalCase.EvalTarget target = c.getTarget();
            if (target == null || target.getMessage() == null || target.getMessage().isBlank()) {
                throw new IllegalArgumentException("case " + c.getId() + " missing target.message");
            }

            String key = target.getConvKey() == null ? "main" : target.getConvKey();
            Long convId = convByKey.computeIfAbsent(key,
                    k -> createIsolatedConversation(userId, vhConfig.versionId()));

            long prevMaxTraceId = currentMaxTraceId(convId);
            ChatService.ChatReply reply = chatService.chatAs(DEFAULT_VH_ID, convId, target.getMessage());

            EvalAssertionEngine.Evidence ev = assertionEngine.collectEvidence(convId, prevMaxTraceId);
            List<String> failures = assertionEngine.assertExpect(c.getExpect(), ev, reply.text());

            result.setConversationId(convId);
            result.setReply(reply.text());
            result.setFailures(failures);
            result.setPassed(failures.isEmpty());
        } catch (Exception e) {
            log.warn("Case {} crashed: {}", c.getId(), e.toString(), e);
            result.setPassed(false);
            result.getFailures().add("exception: " + e.getMessage());
        } finally {
            result.setDurationMs(System.currentTimeMillis() - start);
        }
        return result;
    }

    private void runSetup(EvalCase c, EvalCase.EvalSetup s, long userId,
                          VhActiveConfig vhConfig, Map<String, Long> convByKey) {
        String key = s.getConvKey() == null ? "main" : s.getConvKey();
        Long convId = convByKey.computeIfAbsent(key,
                k -> createIsolatedConversation(userId, vhConfig.versionId()));

        if ("index_episode".equalsIgnoreCase(s.getAction())) {
            String text = s.getEpisodeText();
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException(
                        "case " + c.getId() + " setup action=index_episode requires episodeText");
            }
            // 直接索引一条 episode, 跳过 LLM 摘要环节, 让 episodes 召回断言专注在 Milvus 链路上.
            episodeService.index(userId, convId, text, text, 0, EpisodeService.KIND_FINALIZE);
            return;
        }

        // 默认走完整 chatAs 链路
        if (s.getMessage() != null) {
            chatService.chatAs(DEFAULT_VH_ID, convId, s.getMessage());
        }
        if (s.getMessages() != null) {
            for (String m : s.getMessages()) {
                chatService.chatAs(DEFAULT_VH_ID, convId, m);
            }
        }
    }

    /**
     * 在 sys_user 里 INSERT IGNORE 一行隔离用户. memory_fact / memory_episode 上 user_id 有 FK
     * 指向 sys_user.id, 不预先建好就会跑 facts/episodes case 全部 FK 失败.
     * username UNIQUE(tenant_id, username), 用 case id 派生避免冲突.
     */
    private void ensureSysUser(long userId, String caseId) {
        // username 长度上限 64, 截断保险
        String username = "eval-" + caseId;
        if (username.length() > 64) username = username.substring(0, 64);
        jdbcTemplate.update(
                "INSERT IGNORE INTO sys_user (id, tenant_id, username, role, status) " +
                        "VALUES (?, 1, ?, 'EVAL', 1)",
                userId, username);
    }

    private Long createIsolatedConversation(long userId, Long vhVersionId) {
        LocalDateTime now = LocalDateTime.now();
        Conversation conv = new Conversation();
        conv.setTenantId(1L);
        conv.setVhId(DEFAULT_VH_ID);
        conv.setVhVersionId(vhVersionId);
        conv.setUserId(userId);
        conv.setChannel("EVAL");
        conv.setStatus("ACTIVE");
        conv.setCreatedAt(now);
        conv.setLastActiveAt(now);
        conversationMapper.insert(conv);
        return conv.getId();
    }

    private long currentMaxTraceId(Long convId) {
        ExecutionTrace last = executionTraceMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ExecutionTrace>lambdaQuery()
                        .eq(ExecutionTrace::getConversationId, convId)
                        .orderByDesc(ExecutionTrace::getId)
                        .last("LIMIT 1"));
        return last == null ? 0L : last.getId();
    }

    private void printCaseLine(int idx, int total, EvalCase c, EvalReport.CaseResult r) {
        String mark = r.isPassed() ? "[PASS]" : "[FAIL]";
        log.info(String.format("%s %2d/%d %-26s %-18s %dms",
                mark, idx, total, c.getId(), c.getGroup(), r.getDurationMs()));
        if (!r.isPassed()) {
            for (String f : r.getFailures()) {
                log.info("       └─ {}", f);
            }
        }
    }

    private void printSummary(EvalReport report) {
        log.info("================== EVAL SUMMARY ==================");
        log.info("Total: {} | Pass: {} | Fail: {} | Wall-clock: {}",
                report.getTotalCases(), report.getPassed(), report.getFailed(),
                Duration.ofMillis(report.getDurationMs()));
        log.info("--- by group ---");
        for (Map.Entry<String, EvalReport.GroupSummary> e :
                new LinkedHashMap<>(report.getByGroup()).entrySet()) {
            EvalReport.GroupSummary g = e.getValue();
            log.info("  {}: {}/{}", e.getKey(), g.getPassed(), g.getTotal());
        }
        log.info("==================================================");
    }

    private Path writeJsonReport(EvalReport report) throws Exception {
        Path dir = Path.of(reportDir);
        Files.createDirectories(dir);
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(LocalDateTime.now());
        Path out = dir.resolve("eval-" + ts + ".json");
        Files.writeString(out, jsonMapper.writeValueAsString(report));
        return out;
    }
}
