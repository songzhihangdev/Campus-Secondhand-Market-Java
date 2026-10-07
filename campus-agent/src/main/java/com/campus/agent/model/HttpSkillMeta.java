package com.campus.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 技能对应的 HTTP 调用元数据（对应 resources/skills/http_meta.json 中的一个元素）。
 * <p>
 * {@link #skillName} 必须与 {@link LlmSkill#getName()} 完全一致，
 * 启动时由 SkillRegistry 做强一致性校验。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class HttpSkillMeta {

    /** 技能名，与 llm skill name 完全一致 */
    @JsonProperty("skillName")
    private String skillName;

    /** HTTP 方法：GET / POST / PUT / DELETE */
    @JsonProperty("httpMethod")
    private String httpMethod;

    /** 接口路径，不含域名，可含 {占位符}，例如 /addresses/{addressId} */
    @JsonProperty("path")
    private String path;

    /** 路径参数说明：key=路径占位符名，value=含义说明（不参与实际请求，仅作文档） */
    @JsonProperty("pathParams")
    private Map<String, String> pathParams;

    /** 固定请求头，例如 Content-Type: application/json */
    @JsonProperty("fixedHeaders")
    private Map<String, String> fixedHeaders;

    /** 读超时时间（毫秒），由执行器按技能单独设置 */
    @JsonProperty("timeout")
    private Integer timeout;

    /** 请求体类型：none | json | form */
    @JsonProperty("requestBodyType")
    private String requestBodyType;

    /** 是否需要携带 token（需要时追加 Authorization: Bearer {token}） */
    @JsonProperty("needToken")
    private Boolean needToken;

    /** 接口备注 */
    @JsonProperty("desc")
    private String desc;

    /** 是否需要 token，空值按 false 处理，避免 NPE */
    public boolean isNeedToken() {
        return Boolean.TRUE.equals(needToken);
    }

    /** 请求体类型，空值按 none 处理 */
    public String getRequestBodyType() {
        return requestBodyType == null ? "none" : requestBodyType.toLowerCase();
    }

    /** 超时时间，空值给一个安全默认值 3000ms */
    public int getTimeoutValue() {
        return timeout == null ? 3000 : timeout;
    }
}
