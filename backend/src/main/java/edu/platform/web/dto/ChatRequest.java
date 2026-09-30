package edu.platform.web.dto;

/** POST /api/agent/chat 请求体。 */
public record ChatRequest(String sessionId, String message) {
}
