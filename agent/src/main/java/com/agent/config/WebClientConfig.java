package com.agent.config;

import com.amazon.SellingPartnerAPIAA.LWAAuthorizationCredentials;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.spapi.api.easyship.v2022_03_23.EasyShipApi;
import software.amazon.spapi.api.listings.items.v2021_08_01.ListingsApi;
import software.amazon.spapi.api.orders.v2026_01_01.GetOrderApi;
import software.amazon.spapi.api.orders.v2026_01_01.SearchOrdersApi;
import software.amazon.spapi.api.reports.v2021_06_30.ReportsApi;

@Configuration
@Profile("amazon")
@EnableConfigurationProperties(AmazonSpApiProperties.class)
public class WebClientConfig {

    @Bean
    WebClient amazonLwaWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl("https://api.amazon.com")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .build();
    }

    @Bean
    LWAAuthorizationCredentials amazonSpApiLwaCredentials(AmazonSpApiProperties properties) {
        return LWAAuthorizationCredentials.builder()
                .clientId(properties.getLwaClientId())
                .clientSecret(properties.getLwaClientSecret())
                .refreshToken(properties.getLwaRefreshToken())
                .endpoint("https://api.amazon.com/auth/o2/token")
                .build();
    }

    @Bean
    SearchOrdersApi amazonSearchOrdersApi(
            LWAAuthorizationCredentials credentials,
            AmazonSpApiProperties properties
    ) {
        return new SearchOrdersApi.Builder()
                .lwaAuthorizationCredentials(credentials)
                .endpoint(properties.getEndpoint())
                .build();
    }

    @Bean
    GetOrderApi amazonGetOrderApi(
            LWAAuthorizationCredentials credentials,
            AmazonSpApiProperties properties
    ) {
        return new GetOrderApi.Builder()
                .lwaAuthorizationCredentials(credentials)
                .endpoint(properties.getEndpoint())
                .build();
    }

    @Bean
    EasyShipApi amazonEasyShipApi(
            LWAAuthorizationCredentials credentials,
            AmazonSpApiProperties properties
    ) {
        return new EasyShipApi.Builder()
                .lwaAuthorizationCredentials(credentials)
                .endpoint(properties.getEndpoint())
                .build();
    }

    @Bean
    ReportsApi amazonReportsApi(
            LWAAuthorizationCredentials credentials,
            AmazonSpApiProperties properties
    ) {
        return new ReportsApi.Builder()
                .lwaAuthorizationCredentials(credentials)
                .endpoint(properties.getEndpoint())
                .build();
    }

    @Bean
    ListingsApi amazonListingsApi(LWAAuthorizationCredentials credentials, AmazonSpApiProperties properties) {
        return new ListingsApi.Builder()
                .lwaAuthorizationCredentials(credentials)
                .endpoint(properties.getEndpoint())
                .build();
    }
}
