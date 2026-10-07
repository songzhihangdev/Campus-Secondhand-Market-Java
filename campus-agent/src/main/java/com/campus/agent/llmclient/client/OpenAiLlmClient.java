package com.campus.agent.llmclient.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.llmclient.dto.LlmMessage;
import com.campus.agent.llmclient.dto.LlmRequest;
import com.campus.agent.llmclient.dto.LlmResponse;
import com.campus.agent.llmclient.dto.LlmResult;
import com.campus.agent.llmclient.dto.ToolDefinition;
import com.campus.agent.llmclient.exception.LlmInvokeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 基于 OpenAI 兼容协议的 LLM 客户端实现（支持 DeepSeek / 通义千问）。
 * <p>
 * 使用 {@link RestTemplate} 发起 POST {base-url}/chat/completions，
 * 使用 Jackson 序列化请求/反序列化响应，并把结果区分为“工具调用”或“最终文本”。
 * 所有网络、超时、4xx/5xx、解析错误统一包装为 {@link LlmInvokeException}。
 */
@Slf4j
@Component
public class OpenAiLlmClient implements LlmClient {

    /** OpenAI 兼容的对话补全路径 */
    private static final String CHAT_PATH = "/chat/completions";

    private final LlmProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Spring 容器使用的构造器：按 llm.timeout 自建 RestTemplate。
     * 因本类存在多个构造方法，必须用 {@link Autowired} 显式指定注入哪一个，
     * 否则 Spring 会退而寻找无参构造方法导致启动失败。
     */
    @Autowired
    public OpenAiLlmClient(LlmProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, buildRestTemplate(properties));
    }

    /**
     * 可注入 RestTemplate 的构造器，主要供单元测试传入绑定了 MockRestServiceServer 的实例。
     */
    public OpenAiLlmClient(LlmProperties properties, ObjectMapper objectMapper,
                           RestTemplate restTemplate) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /**
     * 按配置超时构建 RestTemplate（JDK HttpURLConnection 实现，无需额外依赖）。
     */
    private static RestTemplate buildRestTemplate(LlmProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeout());
        factory.setReadTimeout(properties.getTimeout());
        return new RestTemplate(factory);
    }

    @Override
    public LlmResult chat(List<LlmMessage> messages, List<ToolDefinition> tools) {
        if (messages == null || messages.isEmpty()) {
            throw new LlmInvokeException("messages 不能为空");
        }

        // 1. 组装请求体
        LlmRequest request = LlmRequest.builder()
                .model(properties.getModel())
                .messages(messages)
                .tools((tools == null || tools.isEmpty()) ? null : tools)
                .toolChoice((tools == null || tools.isEmpty()) ? null : "auto")
                .stream(false)
                .build();

        // 2. 组装请求头：Bearer 鉴权 + JSON
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());
        HttpEntity<LlmRequest> entity = new HttpEntity<>(request, headers);

        // 3. 发起请求（异常统一转换）
        String url = trimEndSlash(properties.getBaseUrl()) + CHAT_PATH;
        LlmResponse response;
        try {
            ResponseEntity<LlmResponse> resp =
                    restTemplate.postForEntity(url, entity, LlmResponse.class);
            response = resp.getBody();
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // 4xx / 5xx
            log.error("LLM 返回 HTTP 错误：{}，body={}", e.getRawStatusCode(),
                    e.getResponseBodyAsString());
            throw new LlmInvokeException(
                    "LLM 服务返回错误（HTTP " + e.getRawStatusCode() + "）："
                            + e.getResponseBodyAsString(),
                    e.getRawStatusCode(), e);
        } catch (ResourceAccessException e) {
            // 网络异常 / 超时
            log.error("LLM 网络或超时异常", e);
            throw new LlmInvokeException("调用 LLM 失败：网络异常或请求超时，请检查网络与 base-url。", e);
        } catch (Exception e) {
            log.error("LLM 请求发生未知异常", e);
            throw new LlmInvokeException("调用 LLM 发生未知异常：" + e.getMessage(), e);
        }

        // 4. 解析并区分结果类型
        return parseResponse(response);
    }

    /**
     * 把原始响应转换为 {@link LlmResult}。
     */
    private LlmResult parseResponse(LlmResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new LlmInvokeException("LLM 响应为空或缺少 choices");
        }

        LlmResponse.AssistantMessage message = response.getChoices().get(0).getMessage();
        if (message == null) {
            throw new LlmInvokeException("LLM 响应缺少 choices[0].message");
        }

        List<LlmResponse.ToolCall> rawToolCalls = message.getToolCalls();

        // 类型A：存在 tool_calls -> 解析技能名与参数
        if (rawToolCalls != null && !rawToolCalls.isEmpty()) {
            List<LlmResult.ParsedToolCall> parsed = new ArrayList<>();
            for (LlmResponse.ToolCall toolCall : rawToolCalls) {
                if (toolCall.getFunction() == null) {
                    continue;
                }
                String skillName = toolCall.getFunction().getName();
                Map<String, Object> arguments = parseArguments(toolCall.getFunction().getArguments());
                parsed.add(LlmResult.ParsedToolCall.builder()
                        .toolCallId(toolCall.getId())
                        .skillName(skillName)
                        .arguments(arguments)
                        .build());
            }
            if (parsed.isEmpty()) {
                throw new LlmInvokeException("LLM 返回了 tool_calls 但未包含有效 function");
            }
            return LlmResult.toolCalls(parsed);
        }

        // 类型B：最终文本回答
        return LlmResult.text(message.getContent() == null ? "" : message.getContent());
    }

    /**
     * 解析 arguments JSON 字符串为 Map；空串按空 Map 处理，非法 JSON 抛业务异常。
     */
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.error("解析 tool_call arguments 失败：{}", arguments, e);
            throw new LlmInvokeException("解析工具调用参数失败：" + arguments, e);
        }
    }

    private String trimEndSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
