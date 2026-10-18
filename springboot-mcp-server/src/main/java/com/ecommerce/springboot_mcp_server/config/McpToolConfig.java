package com.ecommerce.springboot_mcp_server.config;

import com.ecommerce.springboot_mcp_server.tool.AmazonOrdersTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the AmazonOrdersTools @Tool methods with the MCP server so they are exposed
 * as callable MCP tools over Streamable HTTP/SSE.
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider amazonOrdersToolCallbackProvider(AmazonOrdersTools amazonOrdersTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(amazonOrdersTools)
                .build();
    }
}
