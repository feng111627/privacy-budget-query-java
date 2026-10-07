package com.github.feng111627.privacybudgetqueryjava.config;

/**
 * 一版脱敏/预算参数（不可变）。
 *
 * <p>参数按版本发布：每次发布生成一个新版本号，新版本只影响发布之后的查询，
 * 历史结论仍按当时版本的参数复现。
 *
 * @param version       参数版本号（全局单调递增，从 1 开始；0 表示内置默认版）
 * @param epsilonPerQuery 单次查询消耗的隐私预算 ε
 * @param minGroupSize  最小分组样本量门槛，低于门槛直接拒绝
 * @param maxValue      单条记录取值截断上下限 ±maxValue，用于限定敏感度
 */
public record QueryParameters(long version,
                              double epsilonPerQuery,
                              int minGroupSize,
                              double maxValue) {

    /** 内置默认参数（版本 0），与第一轮行为保持一致。 */
    public static final QueryParameters DEFAULTS =
            new QueryParameters(0L, 0.1, 5, 1000.0);

    /**
     * 用新版本号和覆盖项派生一版参数；覆盖项为 null 时沿用上一版取值。
     */
    public QueryParameters withVersion(long newVersion,
                                       Double epsilonPerQueryOverride,
                                       Integer minGroupSizeOverride,
                                       Double maxValueOverride) {
        return new QueryParameters(newVersion,
                epsilonPerQueryOverride == null ? epsilonPerQuery : epsilonPerQueryOverride,
                minGroupSizeOverride == null ? minGroupSize : minGroupSizeOverride,
                maxValueOverride == null ? maxValue : maxValueOverride);
    }
}
