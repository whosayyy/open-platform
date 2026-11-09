package com.yin.ai.platform.gateway.stream;

/**
 * Optional durable history bridge for stream packets.
 *
 * <p>The platform module owns event production while the service module owns
 * the Redis/WebSocket delivery implementation.</p>
 */
public interface StreamPacketHistoryWriter {

    void publish(String taskId, StreamPacket packet);
}
