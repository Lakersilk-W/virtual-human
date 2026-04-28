package com.vh.web;

import com.vh.runtime.chat.ChatService;
import com.vh.runtime.config.VhActiveConfig;
import com.vh.runtime.config.VhConfigLoader;
import com.vh.runtime.intent.IntentResult;
import com.vh.runtime.intent.IntentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/virtual-humans")
@RequiredArgsConstructor
public class VirtualHumanController {

    private final VhConfigLoader vhConfigLoader;
    private final ChatService chatService;
    private final IntentService intentService;

    /**
     * 取虚拟人激活版本的完整配置.
     * 默认走 DRAFT 通道(W1 还没"发布"流程, 草稿是唯一可用版本).
     */
    @GetMapping("/{vhId}/active-config")
    public VhActiveConfig activeConfig(
            @PathVariable Long vhId,
            @RequestParam(defaultValue = "DRAFT") VhConfigLoader.Channel channel
    ) {
        return vhConfigLoader.load(vhId, channel);
    }

    /**
     * 以该虚拟人口吻进行多轮对话.
     *
     * <p>请求体:
     * <pre>{
     *   "message": "...",
     *   "conversationId": 42       // 可选, 不传则新开会话
     * }</pre>
     *
     * <p>响应包含 conversationId, 客户端下一轮把它传回即可续接.
     */
    @PostMapping("/{vhId}/chat")
    public ChatService.ChatReply chat(
            @PathVariable Long vhId,
            @Valid @RequestBody ChatRequestDto req
    ) {
        return chatService.chatAs(vhId, req.conversationId(), req.message());
    }

    public record ChatRequestDto(
            @NotBlank(message = "message 不能为空") String message,
            Long conversationId
    ) {}

    /**
     * 意图调试: 单独调用意图智能体, 看分类结果, 不进入主对话流程.
     *
     * <p>用于:
     * <ul>
     *   <li>需求里的"意图调试"功能</li>
     *   <li>面试 demo 时演示意图智能体独立可观测</li>
     *   <li>W2 后续 Supervisor 路由的底层能力</li>
     * </ul>
     */
    @PostMapping("/{vhId}/classify-intent")
    public IntentResult classifyIntent(
            @PathVariable Long vhId,
            @Valid @RequestBody IntentRequestDto req
    ) {
        return intentService.classify(vhId, req.message());
    }

    public record IntentRequestDto(
            @NotBlank(message = "message 不能为空") String message
    ) {}

    /**
     * SSE 流式版本. 同 /chat 但回包以 text/event-stream 形式逐 token 返回.
     *
     * <p>事件:
     * <ul>
     *   <li><b>chunk</b>: data 是模型当次回吐的字符片段</li>
     *   <li><b>done</b>:  data 是完整 ChatReply JSON, 含 conversationId/durationMs/tokens</li>
     * </ul>
     *
     * <p>暂不接工具 (W2 之后会合并 ReAct + 流式).
     */
    @PostMapping(value = "/{vhId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @PathVariable Long vhId,
            @Valid @RequestBody ChatRequestDto req
    ) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 min
        emitter.onTimeout(emitter::complete);
        emitter.onError(t -> log.warn("SSE error: {}", t.toString()));

        chatService.chatAsStream(vhId, req.conversationId(), req.message(),
                new ChatService.StreamingCallback() {
                    @Override
                    public void onChunk(String chunk) {
                        try {
                            emitter.send(SseEmitter.event().name("chunk").data(chunk));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onComplete(ChatService.ChatReply reply) {
                        try {
                            emitter.send(SseEmitter.event().name("done").data(reply));
                        } catch (IOException e) {
                            log.warn("Failed to send done event: {}", e.toString());
                        } finally {
                            emitter.complete();
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        emitter.completeWithError(error);
                    }
                });

        return emitter;
    }
}
