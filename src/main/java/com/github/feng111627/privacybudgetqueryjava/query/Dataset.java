package com.github.feng111627.privacybudgetqueryjava.query;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存数据集：字段 -> (分组键 -> 样本值列表)。
 * 分组键为 null 的数据归入默认分组 ""（全量）。
 */
@Component
public class Dataset {

    public static final String DEFAULT_GROUP = "";

    private final Map<String, Map<String, List<Double>>> tables = new ConcurrentHashMap<>();

    public void put(String field, String groupKey, List<Double> values) {
        tables.computeIfAbsent(field, k -> new ConcurrentHashMap<>())
              .put(groupKey == null ? DEFAULT_GROUP : groupKey, List.copyOf(values));
    }

    /**
     * 返回字段的全部分组数据；字段不存在时返回空 Map。
     */
    public Map<String, List<Double>> column(String field) {
        return tables.getOrDefault(field, Map.of());
    }
}
