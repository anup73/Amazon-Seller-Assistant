package com.agent.service;

import com.agent.config.AmazonSpApiProperties;
import com.agent.dto.SearchOrdersRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.spapi.ApiException;
import software.amazon.spapi.api.easyship.v2022_03_23.EasyShipApi;
import software.amazon.spapi.api.listings.items.v2021_08_01.ListingsApi;
import software.amazon.spapi.api.orders.v2026_01_01.GetOrderApi;
import software.amazon.spapi.api.orders.v2026_01_01.SearchOrdersApi;
import software.amazon.spapi.models.easyship.v2022_03_23.ModelPackage;
import software.amazon.spapi.models.orders.v2026_01_01.GetOrderResponse;
import software.amazon.spapi.models.orders.v2026_01_01.Order;
import software.amazon.spapi.models.orders.v2026_01_01.SearchOrdersResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Profile("amazon")
public class AmazonOrdersService {

    private static final Logger log = LoggerFactory.getLogger(AmazonOrdersService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SearchOrdersApi searchOrdersApi;
    private final GetOrderApi getOrderApi;
    private final ListingsApi listingsApi;
    private final EasyShipApi easyShipApi;
    private final AmazonSpApiProperties properties;
    private final ObjectMapper objectMapper;
    private final AmazonOrderEmailService amazonOrderEmailService;

    public AmazonOrdersService(
            SearchOrdersApi searchOrdersApi,
            GetOrderApi getOrderApi,
            ListingsApi listingsApi,
            EasyShipApi easyShipApi,
            AmazonSpApiProperties properties,
            ObjectMapper objectMapper,
            AmazonOrderEmailService amazonOrderEmailService
    ) {
        this.searchOrdersApi = searchOrdersApi;
        this.getOrderApi = getOrderApi;
        this.listingsApi = listingsApi;
        this.easyShipApi = easyShipApi;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.amazonOrderEmailService = amazonOrderEmailService;
    }

    public Mono<Map<String, Object>> getDeliveredOrdersLastTwoMonths() {
        return Mono.fromCallable(() -> {
            OffsetDateTime through = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(3);
            OffsetDateTime since = through.minusMonths(2);
            List<Order> orders = fetchOrders(since, through,
                    List.of("SHIPPED", "PARTIALLY_SHIPPED"), List.of("PACKAGES"));
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Order order : orders) {
                boolean delivered = order.getPackages() != null && order.getPackages().stream()
                        .anyMatch(pkg -> pkg.getPackageStatus() != null
                                && "DELIVERED".equalsIgnoreCase(String.valueOf(pkg.getPackageStatus().getStatus())));
                if (!delivered || order.getOrderId() == null) continue;
                matches.add(objectMapper.convertValue(order, MAP_TYPE));
            }
            return buildOrderSearchResult(since, through, matches);
        }).subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> logSpApiError("getDeliveredOrdersLastTwoMonths", error));
    }

    public Mono<Map<String, Object>> getCancelledOrdersLastTwoMonths() {
        return Mono.fromCallable(() -> {
            OffsetDateTime through = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(3);
            OffsetDateTime since = through.minusMonths(2);
            List<Order> orders = fetchOrders(since, through, List.of("CANCELLED"), List.of());
            List<Map<String, Object>> cancelledOrders = orders.stream()
                    .map(order -> objectMapper.convertValue(order, MAP_TYPE))
                    .toList();
            return buildOrderSearchResult(since, through, cancelledOrders);
        }).subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> logSpApiError("getCancelledOrdersLastTwoMonths", error));
    }

    private Map<String, Object> buildOrderSearchResult(
            OffsetDateTime since,
            OffsetDateTime through,
            List<Map<String, Object>> orders
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", since.toString());
        result.put("through", through.toString());
        result.put("count", orders.size());
        result.put("orders", orders);
        return result;
    }

    public Mono<List<Map<String, Object>>> getAllProductsWithInventory() {
        return Mono.fromCallable(() -> {
            if (properties.getSellerId() == null || properties.getSellerId().isBlank()) {
                throw new IllegalStateException("Set amazon.sp-api.seller-id or AMAZON_SELLER_ID before calling this endpoint");
            }

            List<software.amazon.spapi.models.listings.items.v2021_08_01.Item> items = fetchListingsItems();

            List<Map<String, Object>> products = new ArrayList<>();
            for (var item : items) {
                String sku = item.getSku();
                var marketplaceSummary = item.getSummaries() == null ? null : item.getSummaries().stream()
                        .filter(summary -> properties.getMarketplaceId().equals(summary.getMarketplaceId()))
                        .findFirst().orElse(null);

                Map<String, Object> product = new LinkedHashMap<>();
                product.put("productName", marketplaceSummary == null || marketplaceSummary.getItemName() == null
                        ? sku : marketplaceSummary.getItemName());
                product.put("sku", sku);

                Map<String, Object> inventory = new LinkedHashMap<>();
                if (item.getFulfillmentAvailability() != null && !item.getFulfillmentAvailability().isEmpty()) {
                    List<Map<String, Object>> availability = new ArrayList<>();
                    for (var channel : item.getFulfillmentAvailability()) {
                        Map<String, Object> channelInventory = new LinkedHashMap<>();
                        channelInventory.put("fulfillmentChannelCode", channel.getFulfillmentChannelCode());
                        channelInventory.put("quantity", channel.getQuantity());
                        availability.add(channelInventory);
                    }
                    inventory.put("listingAvailability", availability);
                }
                product.put("inventory", inventory);

                if (marketplaceSummary != null && marketplaceSummary.getStatus() != null
                        && !marketplaceSummary.getStatus().isEmpty()) {
                    product.put("productStatus", marketplaceSummary.getStatus());
                }
                products.add(product);
            }

            log.info("Completed SP-API products and inventory lookup: listingCount={}", products.size());
            return products;
        }).subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> logSpApiError("getAllProductsWithInventory", error));
    }

    public Mono<List<Map<String, Object>>> getAllProductsListings() {
        return Mono.fromCallable(() -> {
            if (properties.getSellerId() == null || properties.getSellerId().isBlank()) {
                throw new IllegalStateException("Set amazon.sp-api.seller-id or AMAZON_SELLER_ID before calling this endpoint");
            }
            List<software.amazon.spapi.models.listings.items.v2021_08_01.Item> items = fetchListingsItems();
            List<Map<String, Object>> products = new ArrayList<>();
            for (var item : items) {
                String sku = item.getSku();
                var marketplaceSummary = item.getSummaries() == null ? null : item.getSummaries().stream()
                        .filter(summary -> properties.getMarketplaceId().equals(summary.getMarketplaceId()))
                        .findFirst().orElse(null);

                Map<String, Object> product = new LinkedHashMap<>();
                product.put("productName", marketplaceSummary == null || marketplaceSummary.getItemName() == null
                        ? sku : marketplaceSummary.getItemName());
                product.put("sku", sku);
                
                if (item.getFulfillmentAvailability() != null && !item.getFulfillmentAvailability().isEmpty()) {
                    List<Map<String, Object>> availability = new ArrayList<>();
                    for (var channel : item.getFulfillmentAvailability()) {
                        Map<String, Object> channelInventory = new LinkedHashMap<>();
                        channelInventory.put("fulfillmentChannelCode", channel.getFulfillmentChannelCode());
                        channelInventory.put("quantity", channel.getQuantity());
                        availability.add(channelInventory);
                    }
                    product.put("listingAvailability", availability);
                }

                if (marketplaceSummary != null && marketplaceSummary.getStatus() != null
                        && !marketplaceSummary.getStatus().isEmpty()) {
                    product.put("productStatus", marketplaceSummary.getStatus());
                }
                products.add(product);
            }
            return products;
        }).subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> logSpApiError("getAllProductsListings", error));
    }

    private List<software.amazon.spapi.models.listings.items.v2021_08_01.Item> fetchListingsItems() throws Exception {
        List<software.amazon.spapi.models.listings.items.v2021_08_01.Item> items = new ArrayList<>();
        Set<String> seenTokens = new HashSet<>();
        String pageToken = null;
        do {
            software.amazon.spapi.models.listings.items.v2021_08_01.ItemSearchResults page;
            try {
                page = listingsApi.searchListingsItems(
                        properties.getSellerId(), List.of(properties.getMarketplaceId()), null,
                        List.of("summaries", "fulfillmentAvailability"), null, null, null, null,
                        null, null, null, null, null, null, null,
                        "sku", "ASC", 20, pageToken
                );
            } catch (ApiException apiError) {
                logSpApiError("searchListingsItems sellerId=" + properties.getSellerId()
                        + ", marketplaceId=" + properties.getMarketplaceId(), apiError);
                throw apiError;
            }
            if (page.getItems() != null) items.addAll(page.getItems());
            pageToken = page.getPagination() == null ? null : page.getPagination().getNextToken();
            if (pageToken != null && !seenTokens.add(pageToken)) {
                throw new IllegalStateException("Amazon returned a repeated listings pagination token");
            }
        } while (pageToken != null && !pageToken.isBlank());
        return items;
    }

    private List<Order> fetchOrders(
            OffsetDateTime since,
            OffsetDateTime through,
            List<String> statuses,
            List<String> includedData
    ) throws Exception {
        List<Order> orders = new ArrayList<>();
        String token = null;
        Set<String> seenTokens = new HashSet<>();
        do {
            SearchOrdersResponse response;
            try {
                response = searchOrdersApi.searchOrders(
                        null, null, since, through, statuses,
                        List.of(properties.getMarketplaceId()), null, 100, token, includedData
                );
            } catch (ApiException apiError) {
                logSpApiError("searchOrders lastUpdatedAfter=" + since + ", lastUpdatedBefore=" + through
                        + ", fulfillmentStatuses=" + statuses + ", marketplaceId=" + properties.getMarketplaceId()
                        + ", includedData=" + includedData, apiError);
                throw apiError;
            }
            if (response.getOrders() != null) orders.addAll(response.getOrders());
            log.info("SP-API searchOrders response: fulfillmentStatuses={}, pageOrderCount={}, hasNextPage={}",
                    statuses, response.getOrders() == null ? 0 : response.getOrders().size(),
                    response.getPagination() != null && response.getPagination().getNextToken() != null);
            token = response.getPagination() == null ? null : response.getPagination().getNextToken();
            if (token != null && !seenTokens.add(token)) {
                throw new IllegalStateException("Amazon returned a repeated orders pagination token");
            }
        } while (token != null && !token.isBlank());
        return orders;
    }

    public Mono<Map<String, Object>> searchOrders(SearchOrdersRequest request) {
        log.info("Searching Amazon orders");
        return Mono.fromCallable(() -> {
                    SearchOrdersResponse response = searchOrdersApi.searchOrders(
                            request.getCreatedAfter(),
                            request.getCreatedBefore(),
                            null,
                            null,
                            request.getFulfillmentStatuses(),
                            List.of(properties.getMarketplaceId()),
                            request.getFulfilledBy(),
                            request.getMaxResultsPerPage(),
                            request.getPaginationToken(),
                            request.getIncludedData()
                    );
                    log.info("SP-API searchOrders response: orderCount={}, hasNextPage={}",
                            response.getOrders() == null ? 0 : response.getOrders().size(),
                            response.getPagination() != null && response.getPagination().getNextToken() != null);
                    return objectMapper.convertValue(response, MAP_TYPE);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> logSpApiError("searchOrders", error));
    }

    public Mono<Map<String, Object>> getOrder(String orderId, List<String> includedData) {
        log.info("Fetching Amazon order {}", orderId);
        return Mono.fromCallable(() -> {
                    GetOrderResponse response = getOrderApi.getOrder(orderId, includedData);
                    log.info("SP-API getOrder response: orderId={}, returnedOrder={}", orderId, response.getOrder() != null);
                    Map<String, Object> result = objectMapper.convertValue(response, MAP_TYPE);
                    amazonOrderEmailService.emailOrderPdf(orderId, result);
                    return result;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> logSpApiError("getOrder orderId=" + orderId, error));
    }

    public Mono<Map<String, Object>> getScheduledEasyShipPackage(String orderId) {
        log.info("Fetching Easy Ship scheduled package for order {}", orderId);
        return Mono.fromCallable(() -> {
                    ModelPackage response = easyShipApi.getScheduledPackage(orderId, properties.getMarketplaceId());
                    log.info("SP-API getScheduledPackage response received: orderId={}", orderId);
                    return objectMapper.convertValue(response, MAP_TYPE);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> log.error(
                        "Easy Ship scheduled package fetch failed for order {}: {}",
                        orderId,
                        error.getMessage()
                ));
    }

    public Mono<Map<String, Object>> getEasyShipPickupsToday() {
        return getEasyShipPickupsOnDate(LocalDate.now(ZoneId.of(properties.getTimeZone())));
    }

    public Mono<Map<String, Object>> getEasyShipPickupsTomorrow() {
        return getEasyShipPickupsOnDate(LocalDate.now(ZoneId.of(properties.getTimeZone())).plusDays(1));
    }

    public Mono<Map<String, Object>> getEasyShipPickupsOnDate(LocalDate shipByDate) {
        return Mono.fromCallable(() -> {
                    ZoneId timeZone = ZoneId.of(properties.getTimeZone());
                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                    OffsetDateTime createdAfter = now.minusYears(2);
                    OffsetDateTime createdBefore = now.minusMinutes(2);
                    log.info("Starting ship-by order search: shipByDate={}, timeZone={}, marketplaceId={}, fulfilledBy={}, createdAfter={}, createdBefore={}",
                            shipByDate, timeZone.getId(), properties.getMarketplaceId(),
                            List.of("MERCHANT"),
                            createdAfter, createdBefore);
                    String paginationToken = null;
                    Set<String> seenTokens = new HashSet<>();
                    List<Map<String, Object>> matchingOrders = new ArrayList<>();
                    int pageNumber = 0;

                    do {
                        pageNumber++;
                        log.info("Requesting Amazon orders page {} for ship-by date {}{}",
                                pageNumber, shipByDate, paginationToken == null ? " (first page)" : " (next page)");
                        SearchOrdersResponse page = searchOrdersApi.searchOrders(
                                createdAfter,
                                createdBefore,
                                null,
                                null,
                                null,
                                List.of(properties.getMarketplaceId()),
                                List.of("MERCHANT"),
                                100,
                                paginationToken,
                                List.of("FULFILLMENT")
                        );

                        int receivedCount = page.getOrders() == null ? 0 : page.getOrders().size();
                        int matchesBeforePage = matchingOrders.size();
                        if (page.getOrders() != null) {
                            for (Order order : page.getOrders()) {
                                if (order.getFulfillment() == null
                                        || order.getFulfillment().getShipByWindow() == null
                                        || order.getFulfillment().getShipByWindow().getLatestDateTime() == null) {
                                    continue;
                                }
                                LocalDate latestShipDate = order.getFulfillment().getShipByWindow()
                                        .getLatestDateTime().atZoneSameInstant(timeZone).toLocalDate();
                                if (shipByDate.equals(latestShipDate)) {
                                    matchingOrders.add(objectMapper.convertValue(order, MAP_TYPE));
                                }
                            }
                        }
                        log.info("Amazon orders page {} received {} orders; {} matched ship-by date {}",
                                pageNumber, receivedCount, matchingOrders.size() - matchesBeforePage, shipByDate);

                        paginationToken = page.getPagination() == null
                                ? null
                                : page.getPagination().getNextToken();
                        if (paginationToken != null && !seenTokens.add(paginationToken)) {
                            throw new IllegalStateException("Amazon returned a repeated orders pagination token");
                        }
                    } while (paginationToken != null && !paginationToken.isBlank());

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("shipByDate", shipByDate.toString());
                    result.put("timeZone", timeZone.getId());
                    result.put("count", matchingOrders.size());
                    result.put("orders", matchingOrders);
                    log.info("Completed ship-by order search: shipByDate={}, pages={}, matchingOrders={}",
                            shipByDate, pageNumber, matchingOrders.size());
                    return result;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> {
                    if (error instanceof ApiException apiError) {
                        logSpApiError("getEasyShipPickupsOnDate shipByDate=" + shipByDate, apiError);
                    } else {
                        log.error("Failed to get orders with ship-by date {}", shipByDate, error);
                    }
                });
    }

    private void logSpApiError(String operation, Throwable error) {
        if (error instanceof ApiException apiError) {
            log.error("SP-API call failed: operation={}, status={}, responseHeaders={}, responseBody={}",
                    operation, apiError.getCode(), apiError.getResponseHeaders(), apiError.getResponseBody(), apiError);
        } else {
            log.error("SP-API call failed: operation={}, message={}", operation, error.getMessage(), error);
        }
    }
}
