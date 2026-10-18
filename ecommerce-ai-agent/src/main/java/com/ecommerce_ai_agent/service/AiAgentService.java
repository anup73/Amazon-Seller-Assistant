package com.ecommerce_ai_agent.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import com.ecommerce_ai_agent.util.AgentTracer;
import com.ecommerce_ai_agent.controller.ChatController.ChatStreamEvent;
import org.springframework.core.Ordered;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AiAgentService {

    private final ChatClient chatClient;
    private final RagClientService ragClientService;
    private final AgentTracer tracer;
    private final ProductInventoryReportService productInventoryReportService;
    private final OrderSummaryReportService orderSummaryReportService;

    public AiAgentService(ChatClient.Builder builder, ToolCallbackProvider mcpToolCallbacks,
            RagClientService ragClientService, AgentTracer tracer,
            ProductInventoryReportService productInventoryReportService,
            OrderSummaryReportService orderSummaryReportService,
            @org.springframework.beans.factory.annotation.Value("${chat.memory.max-messages:50}") int maxMessages) {
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(maxMessages)
                .build();
        this.ragClientService = ragClientService;
        this.tracer = tracer;
        this.productInventoryReportService = productInventoryReportService;
        this.orderSummaryReportService = orderSummaryReportService;
        ToolCallback ragTool = FunctionToolCallback.builder("ragSearchFunction", this::ragSearchFunction)
                .description("Search the knowledge base for store policies and general store information. Provide query.")
                .inputType(RagRequest.class)
                .build();
        this.chatClient = builder
                .defaultSystem("You are a helpful e-commerce AI agent. " +
                        "You have access to tools via an MCP server to manage orders, products, and payments, " +
                        "and a RAG service to answer general knowledge base queries. " +
                        "Use the available MCP tools for live order, customer, payment, and inventory/stock operations; " +
                        "use ragSearchFunction for product catalog and discovery questions (browsing products, categories, " +
                        "specs, descriptions, comparisons, SKU lookups, recommendations) and for store policies " +
                        "(returns, shipping, warranty). Search RAG first for any product search by name, category or SKU; " +
                        "use MCP only when live stock or order data is needed. " +
                        "If a tool fails, say so clearly.")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(mcpToolCallbacks, ragTool)
                .build();
    }

    public String chat(String conversationId, String userMessage) {
        tracer.trace("USER_QUERY", userMessage);
        try {
            if (productInventoryReportService.isProductInventoryReportRequest(userMessage)) {
                return productInventoryReportService.generate(userMessage);
            }
            if (orderSummaryReportService.isOrderSummaryReportRequest(userMessage)) {
                return orderSummaryReportService.generate(userMessage);
            }
            String response = chatClient.prompt()
                    .user(userMessage)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();
            tracer.trace("LLM_RESPONSE", response);
            return response;
        } catch (Exception e) {
            tracer.trace("ERROR", e.getMessage());
            throw e;
        }
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> streamChat(String conversationId, String userMessage) {
        tracer.trace("USER_QUERY", userMessage);
        Sinks.Many<ServerSentEvent<ChatStreamEvent>> activityEvents = Sinks.many().multicast().onBackpressureBuffer();
        AtomicBoolean toolCallSeen = new AtomicBoolean();
        AtomicBoolean answerStarted = new AtomicBoolean();
        AtomicBoolean answerPreparationAnnounced = new AtomicBoolean();
        AtomicBoolean toolActivityAnnounced = new AtomicBoolean();

        StreamAdvisor toolActivityAdvisor = new ToolActivityAdvisor(activityEvents, toolCallSeen,
                answerPreparationAnnounced, toolActivityAnnounced);

        boolean productInventoryReport = productInventoryReportService.isProductInventoryReportRequest(userMessage);
        boolean orderSummaryReport = orderSummaryReportService.isOrderSummaryReportRequest(userMessage);
        Flux<ServerSentEvent<ChatStreamEvent>> response = Flux.defer(() -> {
            if (productInventoryReport) {
                activityEvents.tryEmitNext(event("status", "Checking product and inventory information…"));
            } else if (orderSummaryReport) {
                activityEvents.tryEmitNext(event("status", "Analyzing order summary and trends…"));
            }
            Flux<String> generatedContent = productInventoryReport
                    ? productInventoryReportService.stream(userMessage,
                            status -> activityEvents.tryEmitNext(event("status", status)))
                    : orderSummaryReport
                            ? orderSummaryReportService.stream(userMessage,
                                    status -> activityEvents.tryEmitNext(event("status", status)))
                            : chatClient.prompt()
                                    .user(userMessage)
                                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)
                                            .advisors(toolActivityAdvisor))
                                    .stream()
                                    .content();
            return generatedContent
                .filter(content -> content != null && !content.isEmpty())
                .map(content -> {
                    if (answerStarted.compareAndSet(false, true)) {
                        String status = (productInventoryReport || orderSummaryReport) || toolCallSeen.get()
                                ? "Writing up the information…"
                                : "Writing your answer…";
                        activityEvents.tryEmitNext(event("status", status));
                    }
                    return event("content", content);
                })
                .doOnComplete(() -> tracer.trace("LLM_RESPONSE", "Stream completed"))
                .onErrorResume(error -> {
                    tracer.trace("ERROR", error.getMessage());
                    return Flux.just(event("error", "Sorry, I encountered an error. Please try again."));
                })
                .doFinally(signal -> activityEvents.tryEmitComplete());
        });

        return Flux.concat(
                        Mono.just(event("status", "Understanding your request…")),
                        Flux.merge(activityEvents.asFlux(), response),
                        Mono.just(event("done", "")));
    }

    private ServerSentEvent<ChatStreamEvent> event(String type, String content) {
        return ServerSentEvent.<ChatStreamEvent>builder()
                .event(type)
                .data(new ChatStreamEvent(type, content))
                .build();
    }

    private class ToolActivityAdvisor implements StreamAdvisor {
        private final Sinks.Many<ServerSentEvent<ChatStreamEvent>> activityEvents;
        private final AtomicBoolean toolCallSeen;
        private final AtomicBoolean answerPreparationAnnounced;
        private final AtomicBoolean toolActivityAnnounced;

        private ToolActivityAdvisor(Sinks.Many<ServerSentEvent<ChatStreamEvent>> activityEvents,
                AtomicBoolean toolCallSeen, AtomicBoolean answerPreparationAnnounced,
                AtomicBoolean toolActivityAnnounced) {
            this.activityEvents = activityEvents;
            this.toolCallSeen = toolCallSeen;
            this.answerPreparationAnnounced = answerPreparationAnnounced;
            this.toolActivityAnnounced = toolActivityAnnounced;
        }

        @Override
        public String getName() {
            return "chatProgressAdvisor";
        }

        @Override
        public int getOrder() {
            // Run inside Spring AI's ToolCallingAdvisor so intermediate tool-call responses are visible here.
            return Ordered.HIGHEST_PRECEDENCE + 400;
        }

        @Override
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
            if (toolCallSeen.get() && answerPreparationAnnounced.compareAndSet(false, true)) {
                activityEvents.tryEmitNext(event("status", "Putting the information together…"));
            }

            return chain.nextStream(request).doOnNext(response -> {
                if (response.chatResponse() != null && response.chatResponse().hasToolCalls()) {
                    toolCallSeen.set(true);
                    if (toolActivityAnnounced.compareAndSet(false, true)) {
                        activityEvents.tryEmitNext(event("status", "Checking the relevant information…"));
                    }
                }
            });
        }
    }

    public record RagRequest(
            @org.springframework.ai.tool.annotation.ToolParam(description = "Search query about store policies (returns, shipping, warranty, etc.)") String query) {
    }

    public String ragSearchFunction(RagRequest request) {
        String query = request.query();
        tracer.trace("TOOL_CALL", "Calling RAG search with query: " + query);
        String result = ragClientService.searchKnowledgeBase(query);
        tracer.trace("TOOL_RESPONSE", "RAG search returned: " + result);
        return result;
    }
}
