package com.yin.ai.platform.gateway.adapter;

import com.yin.ai.platform.gateway.GatewayMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DeepSeekMessageConverter implements MessageConverter<Message> {

    @Override
    public String adapterName() {
        return "deepseek";
    }

    @Override
    public Message convert(GatewayMessage msg) {
        return switch (msg.getRole()) {
            case system -> new SystemMessage(msg.getContent());
            case user -> new UserMessage(msg.getContent());
            case assistant -> new AssistantMessage(msg.getContent());
            case tool -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            msg.getToolCallId(), msg.getName(), msg.getContent())))
                    .build();
        };
    }
}
