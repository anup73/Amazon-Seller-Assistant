package com.ecommerce.springboot_mcp_server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/**
 * Wires up the outbound OAuth2 client_credentials flow: the MCP server obtains its own
 * access token (client registration "agent-api") and attaches it as a Bearer header when
 * calling the agent API service. Uses the service-based authorized client
 * manager since there is no end-user/session involved - this is a pure machine-to-machine
 * call made from within @Tool method invocations.
 */
@Configuration
public class OAuth2ClientConfig {

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {

        OAuth2AuthorizedClientProvider authorizedClientProvider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(authorizedClientProvider);
        return manager;
    }

    @Bean
    public RestClient amazonOrdersApiRestClient(
            OAuth2AuthorizedClientManager authorizedClientManager,
            @Value("${agent.api.base-url}") String baseUrl) {

        OAuth2ClientHttpRequestInterceptor requestInterceptor =
                new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
        // Every call from this RestClient authenticates as the "agent-api" client
        // registration (client_credentials), regardless of any inbound MCP caller.
        requestInterceptor.setClientRegistrationIdResolver(request -> "agent-api");

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(requestInterceptor)
                .build();
    }
}
