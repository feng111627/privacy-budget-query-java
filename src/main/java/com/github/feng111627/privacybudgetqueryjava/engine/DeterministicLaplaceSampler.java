package com.github.feng111627.privacybudgetqueryjava.engine;

import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * 确定性拉普拉斯噪声采样器。
 *
 * 使用 {@link Random#Random(long)}（线性同余算法，实现受 JDK 规范约束），
 * 因此给定 (seed, scale) 在任意 JVM 上得到完全一致的噪声，支撑同批次相同查询的
 * 稳定、可复现结论。
 *
 * 拉普拉斯分布 Lap(scale) 通过逆变换采样得到：
 *   u ~ Uniform(-0.5, 0.5)
 *   x = -scale * sign(u) * ln(1 - 2|u|)
 */
@Component
public class DeterministicLaplaceSampler implements NoiseSampler {

    @Override
    public double nextLaplace(double scale, long seed) {
        if (scale <= 0d) {
            throw new IllegalArgumentException("laplace scale must be positive: " + scale);
        }
        Random random = new Random(seed);
        double u = random.nextDouble() - 0.5d;
        double sign = u < 0d ? -1d : 1d;
        double body = 1.0d - 2.0d * Math.abs(u);
        // nextDouble 的最大值严格小于 1，u 严格在 (-0.5, 0.5)，body 严格大于 0。
        return -scale * sign * Math.log(body);
    }
}
