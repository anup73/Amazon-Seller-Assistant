package com.ecommerce_ai_agent.controller;

import com.ecommerce_ai_agent.service.AiAgentService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AiAgentService aiAgentService;

    public ChatController(AiAgentService aiAgentService) {
        this.aiAgentService = aiAgentService;
    }

    @PostMapping(value = "/message", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> sendMessage(
            @RequestBody String message, jakarta.servlet.http.HttpSession session) {
        return aiAgentService.streamChat(session.getId(), message);
    }

    public record ChatStreamEvent(String type, String content) {}
}
