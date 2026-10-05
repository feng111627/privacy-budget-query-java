package com.github.feng111627.privacybudgetqueryjava.engine;

/** 确定性噪声采样器：给定种子产出可复现的拉普拉斯噪声。 */
public interface NoiseSampler {
    double nextLaplace(double scale, long seed);
}
