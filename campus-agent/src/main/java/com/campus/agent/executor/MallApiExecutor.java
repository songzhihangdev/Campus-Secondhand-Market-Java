package com.campus.agent.executor;

import cn.hutool.json.JSONUtil;
import com.campus.agent.config.MallProperties;
import com.campus.agent.config.RestTemplateFactory;
import com.campus.agent.config.SkillRegistry;
import com.campus.agent.context.MallContextHolder;
import com.campus.agent.model.HttpSkillMeta;
import com.campus.agent.model.LlmSkill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Array;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 商城 HTTP 执行器（核心组件）。
 * <p>
 * 入参：技能名 + 业务参数；出参：接口执行结果文本（observation）。
 * 内部职责：查配置 -> 高风险拦截 -> 路径占位符填充（区分 path 参数与 body/query 参数）
 * -> 构建请求头（含 token）-> 按方法/请求体类型发起请求 -> 异常包装为可读文本。
 * <p>
 * 注意：本类只做“商城接口调用”，不包含任何 Agent / LLM / ReAct 逻辑；
 * 任何异常都不会向上抛出，而是转换为可读文本返回。
 */
@Slf4j
@Component
public class MallApiExecutor {

    /** 匹配路径中的 {占位符} */
    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("\\{([^/}]+)}");

    private final SkillRegistry skillRegistry;
    private final RestTemplateFactory restTemplateFactory;
    private final MallProperties mallProperties;

    public MallApiExecutor(SkillRegistry skillRegistry,
                           RestTemplateFactory restTemplateFactory,
                           MallProperties mallProperties) {
        this.skillRegistry = skillRegistry;
        this.restTemplateFactory = restTemplateFactory;
        this.mallProperties = mallProperties;
    }

    /**
     * 执行指定技能。
     *
     * @param skillName      技能名（与两份配置中的 name/skillName 对应）
     * @param businessParams 业务参数（含路径参数、请求体或查询参数）
     * @return 接口结果文本；高风险或异常时为对应可读提示，永不抛出异常
     */
    public String execute(String skillName, Map<String, Object> businessParams) {
        try {
            // 1. 查询两份配置
            HttpSkillMeta meta = skillRegistry.getHttpMeta(skillName);
            LlmSkill skill = skillRegistry.getLlmSkill(skillName);
            if (meta == null || skill == null) {
                return "【调用失败】未找到技能配置：" + skillName;
            }

            // 2. 高风险拦截：warning 含“高风险”则禁止自动执行
            String warning = skill.getWarning();
            if (warning != null && warning.contains("高风险")) {
                return "【高风险操作，需要人工确认，禁止自动执行】";
            }

            // 复制一份参数，避免污染调用方原始 Map
            Map<String, Object> remaining = businessParams == null
                    ? new HashMap<>() : new HashMap<>(businessParams);//浅拷贝

            // 3. 路径占位符填充：被填充的参数从 remaining 移除，剩下的作为 body/query 参数
            String resolvedPath = resolvePath(meta.getPath(), remaining);

            // 4. 构建请求头（固定头 + token）
            HttpHeaders headers = buildHeaders(meta);

            // 按技能超时创建 RestTemplate
            RestTemplate restTemplate = restTemplateFactory.create(meta.getTimeoutValue());
            HttpMethod method = HttpMethod.valueOf(meta.getHttpMethod().toUpperCase());
            String bodyType = meta.getRequestBodyType();

            URI uri;
            HttpEntity<?> requestEntity;

            if ("json".equals(bodyType)) {
                // 5a. JSON 请求体：剩余参数整体作为 JSON body
                uri = buildUri(resolvedPath, null);
                headers.setContentType(MediaType.APPLICATION_JSON);
                requestEntity = new HttpEntity<>(remaining, headers);

            } else if ("form".equals(bodyType)) {
                // 5b. 表单请求体：剩余参数作为 x-www-form-urlencoded
                uri = buildUri(resolvedPath, null);
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
                remaining.forEach((k, v) -> {
                    if (v != null) {
                        form.add(k, v);
                    }
                });
                requestEntity = new HttpEntity<>(form, headers);

            } else {
                // 5c. 无请求体（GET/DELETE，或 requestBodyType=none 的 PUT）：剩余参数走 query
                uri = buildUri(resolvedPath, remaining);
                requestEntity = new HttpEntity<>(headers);
            }

            // 发起请求，统一以字符串接收响应体
            ResponseEntity<String> response =
                    restTemplate.exchange(uri, method, requestEntity, String.class);
            //todo 测试返回数据
            System.out.println(JSONUtil.toJsonStr(response));
            String body = response.getBody();
            return body == null ? "" : body;

        } catch (HttpClientErrorException.Unauthorized e) {
            // 401：登录失效
            log.warn("调用技能[{}]返回401", skillName);
            return "【调用失败】未授权或登录状态已过期（HTTP 401），请先重新登录后再试。";
        } catch (HttpClientErrorException e) {
            // 其它 4xx
            log.warn("调用技能[{}]返回4xx: {}", skillName, e.getRawStatusCode());
            return "【调用失败】客户端请求错误（HTTP " + e.getRawStatusCode() + "）："
                    + safeBody(e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            // 5xx
            log.error("调用技能[{}]返回5xx: {}", skillName, e.getRawStatusCode());
            return "【调用失败】商城服务异常（HTTP " + e.getRawStatusCode() + "），请稍后重试："
                    + safeBody(e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            // 网络异常 / 连接超时 / 读超时
            log.error("调用技能[{}]发生网络或超时异常", skillName, e);
            String reason = isTimeout(e) ? "请求超时" : "无法连接商城服务（网络异常）";
            return "【调用失败】" + reason + "，请检查网络或商城服务是否可用。";
        } catch (IllegalArgumentException e) {
            // 例如 httpMethod 配置非法
            log.error("调用技能[{}]参数/配置非法", skillName, e);
            return "【调用失败】技能配置或入参有误：" + e.getMessage();
        } catch (Exception e) {
            // 兜底：任何未预期异常都包装为文本，不向上抛
            log.error("调用技能[{}]发生未知异常", skillName, e);
            return "【调用失败】执行接口时发生未知异常：" + e.getMessage();
        }
    }

    /**
     * 路径占位符填充。
     * 从 params 中取占位符对应的值替换到 path，并把这些 key 从 params 移除；
     * 未提供值的占位符替换为空串。
     */
    private String resolvePath(String path, Map<String, Object> params) {
        Matcher matcher = PATH_PLACEHOLDER.matcher(path);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = params.remove(key);
            String encoded = value == null ? ""
                    : URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
            // quoteReplacement 防止值中出现 $ / \ 被当作分组引用
            matcher.appendReplacement(sb, Matcher.quoteReplacement(encoded));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 构建完整 URI。
     *
     * @param resolvedPath 已填充占位符的路径
     * @param queryParams  查询参数（可为 null）；集合/数组按“逗号分隔”拼成单个参数
     */
    private URI buildUri(String resolvedPath, Map<String, Object> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(mallProperties.getBaseUrl() + resolvedPath);
        if (queryParams != null) {
            queryParams.forEach((k, v) -> {
                if (v != null) {
                    builder.queryParam(k, toQueryValue(v));
                }
            });
        }
        return builder.build().encode().toUri();
    }

    /**
     * 查询参数值归一化：集合/数组按英文逗号拼接（对应“ids=1,2,3”这类约定），其余原样返回。
     */
    private Object toQueryValue(Object value) {
        if (value instanceof Collection) {
            return join(((Collection<?>) value));
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            java.util.List<Object> list = new java.util.ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add(Array.get(value, i));
            }
            return join(list);
        }
        return value;
    }

    /** 以逗号拼接非空元素 */
    private String join(Collection<?> collection) {
        return collection.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    /**
     * 构建请求头：先放固定头，再按需追加 Authorization: Bearer {token}。
     */
    private HttpHeaders buildHeaders(HttpSkillMeta meta) {
        HttpHeaders headers = new HttpHeaders();
        if (meta.getFixedHeaders() != null) {
            meta.getFixedHeaders().forEach(headers::add);
        }
        if (meta.isNeedToken()) {
            String token = MallContextHolder.getToken();
            if (token != null && !token.isEmpty()) {
                // setBearerAuth 会生成 “Authorization: Bearer {token}”
                headers.setBearerAuth(token);
            } else {
                // 未取到 token 也继续发起请求，由商城侧返回 401，再统一包装
                log.warn("技能[{}]需要 token，但上下文中不存在", meta.getSkillName());
            }
        }
        return headers;
    }

    /** 根据异常链判断是否为超时 */
    private boolean isTimeout(ResourceAccessException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            String name = cause.getClass().getName();
            if (name.contains("Timeout") || name.contains("TimedOut")) {
                return true;
            }
            cause = cause.getCause();
        }
        return e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout");
    }

    /** 截断过长的错误响应体，避免 observation 被刷屏 */
    private String safeBody(String body) {
        if (body == null || body.isEmpty()) {
            return "（无响应内容）";
        }
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }
}
