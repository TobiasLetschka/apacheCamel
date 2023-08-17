package de.cover.genisys.cover.order.out.routes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.cover.genisys.cover.order.out.models.magento2.Magento2;
import de.cover.genisys.cover.order.out.models.magento2.StatusHistory;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.bson.Document;
import org.bson.json.JsonParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class UpdateMagento2RouteBuilder extends RouteBuilder {

    // Routes
    public static final String UPDATE_ORDER_MAGENTO2 = "direct:UpdateOrderMagento2";

    // IDs
    private static final String UPDATE_ORDER_ID = "updateOrderMagento2Id";


    // Properties
    public static final String ORDER_ID_UNIQUE_PROPERTY = "order_id_unique_Property";

    // Endpoints
    private static final String LOCALHOST = "https://localhost";
    private static final String DIRECT_GET_ORDER_ID_FROM_CACHED_M2_INPUT = "direct:GetOrderIDMagento2FromCachedInput";
    public static final String DIRECT_BUILD_MAGENTO2_ORDER_JSON = "direct:buildMagento2OrderJson";
    public static final String MAGENTO2_JSON_PROPERTY = "magento2Json_Property";


    @Value("${magento2.rest.redelivery.attempts}")
    private int redeliveryAttempts = 2;

    @Value("${magento2.rest.redelivery.delay}")
    private int redeliveryDelay = 1000;

    @Override
    public void configure() throws Exception {
        getContext().setStreamCaching(true);

        onException(HttpOperationFailedException.class)
                .maximumRedeliveries(redeliveryAttempts)
                .redeliveryDelay(redeliveryDelay)
                .setProperty("upsert_failed_property", constant(false))
                .onExceptionOccurred(new Processor() {

                    @Override
                    public void process(Exchange exchange) throws Exception {
                        var exception = (HttpOperationFailedException) exchange.getProperty("CamelExceptionCaught");
                        String statusText = exception.getStatusText();
                        String responseBody = exception.getResponseBody();

                        int statusCode = exception.getStatusCode();
                        log.error(String.format("Product "  +  "- Upsert failed - we make Retry: %s - %s - %s" , statusCode, statusText, responseBody));
                    }
                })
                .continued(true)

        ;

        onException(JsonParseException.class)
                .maximumRedeliveries(redeliveryAttempts)
                .redeliveryDelay(redeliveryDelay)
                .setProperty("upsert_failed_property", constant(false))
                .onExceptionOccurred(p -> {
                    var exchange = p.getIn().getExchange();
                    var exception = exchange.getProperty("CamelExceptionCaught", JsonParseException.class);
                        var exceptionMessage = exception.getMessage();
                        var body = exchange.getIn().getBody();
                        log.error(String.format("Product "  +  "- Upsert failed - we make Retry: %s - %s" , exceptionMessage, body));
                })
                .continued(true)

        ;

        /**
         * Main Route
         */
        from(UPDATE_ORDER_MAGENTO2).routeId(UPDATE_ORDER_ID)
                .log(LoggingLevel.INFO, "Body Update Magento2 ${body}")

                .to(DIRECT_GET_ORDER_ID_FROM_CACHED_M2_INPUT)
                .to(DIRECT_BUILD_MAGENTO2_ORDER_JSON)

                .setBody().exchangeProperty(MAGENTO2_JSON_PROPERTY)

                .setHeader(Exchange.HTTP_METHOD, constant("POST"))

                .setHeader("Authorization", simple("Bearer ${exchangeProperty.ShopAuth_Property}"))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json;charset=utf-8"))
                .setHeader(Exchange.HTTP_URI, simple("${exchangeProperty.ShopUrl_Property}/rest/V1/orders/${exchangeProperty.order_id_unique_Property}/comments"))
                .log(LoggingLevel.DEBUG, "Headers: ${headers}")
                .log(LoggingLevel.DEBUG,"${exchangeProperty.ShopUrl_Property}/rest/V1/orders/${exchangeProperty.order_id_unique_Property}/comments")
                .to(LOCALHOST)
                .log(LoggingLevel.INFO, "Body after Magento2 Update: ${body}")
        ;

        /**
         * Building JSON Body
         */
        from(DIRECT_BUILD_MAGENTO2_ORDER_JSON)
                .setProperty(MAGENTO2_JSON_PROPERTY).exchange(ex -> {


                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                    String text = formatter.format(ZonedDateTime.now());

                    var coverResponse = ex.getProperty(UpsertCoverRouteBuilder.COVER_RESPONSE_PROPERTY, Document.class);
                    var order = coverResponse.get("order", Document.class);
                    var error = order.get("error", Document.class);
                    var errorCode = error.getString("error_code");
                    var errorMessage = error.getString("error_msg");
                    var comment = "";
                    if(Integer.parseInt(errorCode) != 0) {
                        comment = "<b>Response from COVER</b><br> Status: " + errorCode + " Antwort: " + errorMessage;
                    } else {
                        comment = "<b>Response from COVER</b><br> Status: " + errorCode + " Antwort: Bestellung wurde erfolgreich verarbeitet.";
                    }
                    var statusHistory = new StatusHistory(0, text, comment, "complete", 0);
                    var magento2 = new Magento2(statusHistory);

                    String result = "";
                    try {
                       result = new ObjectMapper().writeValueAsString(magento2);
                    } catch (JsonProcessingException e) {

                        log.error("Error! While creating String from Magento2-Record");
                    }
                    return result;
                })

                .setBody().exchangeProperty(MAGENTO2_JSON_PROPERTY)
                .log(LoggingLevel.INFO, "Json body: ${body}")
        ;

        /**
         * Cached Input fetching order_id_unique
         */
        from(DIRECT_GET_ORDER_ID_FROM_CACHED_M2_INPUT)
                .setProperty(ORDER_ID_UNIQUE_PROPERTY).exchange(ex -> {
                    var cachedBody = ex.getProperty(UpsertCoverRouteBuilder.CACHING_BODY_PROPERTY, Document.class);
                    return cachedBody.get("order",Document.class).getString("order_id_unique");
                })
        ;
    }
}
