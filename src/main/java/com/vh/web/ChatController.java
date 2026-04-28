package com.vh.web;

import com.vh.runtime.chat.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 裸模型对话端点 — 不带人设, 用于诊断模型链路.
 * 真业务对话见 {@link VirtualHumanController#chat}.
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ChatService.ChatReply rawChat(@Valid @RequestBody ChatRequestDto req) {
        return chatService.rawChat(req.message());
    }

    public record ChatRequestDto(
            @NotBlank(message = "message 不能为空") String message
    ) {}
}
