package com.example.device.admin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 建档入口的幂等键强制：缺失 4001、格式非法 4002、只拦 POST 建档路径。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class IdempotencyKeyFilterTest {

    private static final String CREATE_PATH = "/api/v1/admin/devices";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final IdempotencyKeyFilter filter = new IdempotencyKeyFilter(objectMapper);

    @Test
    void missingHeaderIsParameterMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", CREATE_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertFalse(chained.get());
        assertEquals(400, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(4001, body.get("code").asInt());
        assertTrue(body.get("message").asText().contains("Idempotency-Key"));
    }

    @Test
    void malformedKeyIsParameterFormatError() throws Exception {
        for (String key : new String[] {"short", "bad key!", "a".repeat(65)}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", CREATE_PATH);
            request.addHeader(IdempotencyKeyFilter.HEADER, key);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chained = new AtomicBoolean();

            filter.doFilter(request, response, (req, res) -> chained.set(true));

            assertFalse(chained.get(), "key must be rejected: " + key);
            assertEquals(400, response.getStatus());
            assertEquals(4002, objectMapper.readTree(response.getContentAsByteArray()).get("code").asInt());
        }
    }

    @Test
    void wellFormedKeyIsPassedThrough() throws Exception {
        for (String key : new String[] {"abcdefgh", "A_b-1234", "a".repeat(64)}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", CREATE_PATH);
            request.addHeader(IdempotencyKeyFilter.HEADER, key);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chained = new AtomicBoolean();

            filter.doFilter(request, response, (req, res) -> chained.set(true));

            assertTrue(chained.get(), "key must be accepted: " + key);
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void otherMethodsAndPathsAreNotFiltered() throws Exception {
        MockHttpServletRequest put = new MockHttpServletRequest("PUT", CREATE_PATH + "/SN-1");
        AtomicBoolean putChained = new AtomicBoolean();
        filter.doFilter(put, new MockHttpServletResponse(), (req, res) -> putChained.set(true));

        MockHttpServletRequest otherPost = new MockHttpServletRequest("POST", "/api/v1/admin/mq/dead-letters/1/replay");
        AtomicBoolean postChained = new AtomicBoolean();
        filter.doFilter(otherPost, new MockHttpServletResponse(), (req, res) -> postChained.set(true));

        assertTrue(putChained.get());
        assertTrue(postChained.get());
    }
}
