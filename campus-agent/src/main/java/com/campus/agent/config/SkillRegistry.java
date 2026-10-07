package com.campus.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.model.HttpSkillMeta;
import com.campus.agent.model.LlmSkill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 技能注册中心：项目启动时读取两份 JSON 配置到内存 Map，并做强一致性校验。
 * <ul>
 *     <li>{@link #llmSkillMap}：key = LlmSkill.name</li>
 *     <li>{@link #httpMetaMap}：key = HttpSkillMeta.skillName</li>
 * </ul>
 * 两边 skillName 集合必须完全匹配，否则抛出启动异常（IllegalStateException），阻止应用启动。
 */
@Slf4j
@Component
public class SkillRegistry {

    private final ObjectMapper objectMapper;
    private final MallProperties mallProperties;

    /** 技能名 -> 面向大模型的技能定义 */
    private final Map<String, LlmSkill> llmSkillMap = new HashMap<>();

    /** 技能名 -> HTTP 调用元数据 */
    private final Map<String, HttpSkillMeta> httpMetaMap = new HashMap<>();

    public SkillRegistry(ObjectMapper objectMapper, MallProperties mallProperties) {
        this.objectMapper = objectMapper;
        this.mallProperties = mallProperties;
    }

    /**
     * 启动时自动加载并校验。声明为 public 以便单元测试中手动触发。
     */
    @PostConstruct
    public void load() {
        loadLlmSkills();
        loadHttpMetas();
        validateNameConsistency();
        log.info("技能配置加载完成，共加载 {} 个技能", llmSkillMap.size());
    }

    /** 读取面向大模型的技能定义数组 */
    private void loadLlmSkills() {
        String location = mallProperties.getSkills().getLlmDef();
        LlmSkill[] skills = readClasspathJson(location, LlmSkill[].class);
        for (LlmSkill skill : skills) {
            if (skill.getName() == null || skill.getName().trim().isEmpty()) {
                throw new IllegalStateException("LLM 技能定义存在空 name，文件：" + location);
            }
            if (llmSkillMap.put(skill.getName(), skill) != null) {
                throw new IllegalStateException("LLM 技能 name 重复：" + skill.getName());
            }
        }
    }

    /** 读取 HTTP 元数据数组 */
    private void loadHttpMetas() {
        String location = mallProperties.getSkills().getHttpMeta();
        HttpSkillMeta[] metas = readClasspathJson(location, HttpSkillMeta[].class);
        for (HttpSkillMeta meta : metas) {
            if (meta.getSkillName() == null || meta.getSkillName().trim().isEmpty()) {
                throw new IllegalStateException("HTTP 元数据存在空 skillName，文件：" + location);
            }
            if (httpMetaMap.put(meta.getSkillName(), meta) != null) {
                throw new IllegalStateException("HTTP 元数据 skillName 重复：" + meta.getSkillName());
            }
        }
    }

    /**
     * 校验两份配置的 skillName 集合完全一致；不一致则启动失败并打印差异。
     */
    private void validateNameConsistency() {
        Set<String> llmNames = new HashSet<>(llmSkillMap.keySet());
        Set<String> httpNames = new HashSet<>(httpMetaMap.keySet());

        Set<String> onlyInLlm = new HashSet<>(llmNames);
        onlyInLlm.removeAll(httpNames);
        Set<String> onlyInHttp = new HashSet<>(httpNames);
        onlyInHttp.removeAll(llmNames);

        if (!onlyInLlm.isEmpty() || !onlyInHttp.isEmpty()) {
            throw new IllegalStateException(
                    "技能配置不一致，启动失败！仅存在于 llm_skill_def 的技能=" + onlyInLlm
                            + "；仅存在于 http_meta 的技能=" + onlyInHttp);
        }
    }

    /** 从 classpath 读取 JSON 并反序列化为数组 */
    private <T> T readClasspathJson(String location, Class<T> targetType) {
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("找不到技能配置文件（classpath）：" + location);
        }
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, targetType);
        } catch (Exception e) {
            throw new IllegalStateException("解析技能配置文件失败：" + location, e);
        }
    }

    public LlmSkill getLlmSkill(String skillName) {
        return llmSkillMap.get(skillName);
    }

    public HttpSkillMeta getHttpMeta(String skillName) {
        return httpMetaMap.get(skillName);
    }

    public Map<String, LlmSkill> getLlmSkillMap() {
        return Collections.unmodifiableMap(llmSkillMap);
    }

    public Map<String, HttpSkillMeta> getHttpMetaMap() {
        return Collections.unmodifiableMap(httpMetaMap);
    }

    public Set<String> skillNames() {
        return Collections.unmodifiableSet(llmSkillMap.keySet());
    }

    public int size() {
        return llmSkillMap.size();
    }

    public Collection<LlmSkill> allLlmSkills() {
        return Collections.unmodifiableCollection(llmSkillMap.values());
    }
}
