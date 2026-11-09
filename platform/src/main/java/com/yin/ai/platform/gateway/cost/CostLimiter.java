package com.yin.ai.platform.gateway.cost;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 成本限制器 — 按用户跟踪累计成本，超限时抛出 {@link CostLimitExceededException}。
 * <p>
 * 当注入 {@link RedissonClient} 时使用 Redis Hash 存储，多实例共享预算；
 * 否则回退到本地 ConcurrentHashMap（适用于单实例或测试）。
 */
@Slf4j
public class CostLimiter {

    private static final String KEY_DAILY = "ai:cost:daily";
    private static final String KEY_MONTHLY = "ai:cost:monthly";
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final double perRequestBudgetUsd;
    private final double perUserDailyBudgetUsd;
    private final double perUserMonthlyBudgetUsd;

    private final RedissonClient redisson;
    private final Map<String, DailyCost> localDaily;
    private final Map<String, MonthlyCost> localMonthly;

    public CostLimiter(double perRequestBudgetUsd, double perUserDailyBudgetUsd,
                       double perUserMonthlyBudgetUsd) {
        this(perRequestBudgetUsd, perUserDailyBudgetUsd, perUserMonthlyBudgetUsd, null);
    }

    public CostLimiter(double perRequestBudgetUsd, double perUserDailyBudgetUsd,
                       double perUserMonthlyBudgetUsd, RedissonClient redisson) {
        this.perRequestBudgetUsd = perRequestBudgetUsd;
        this.perUserDailyBudgetUsd = perUserDailyBudgetUsd;
        this.perUserMonthlyBudgetUsd = perUserMonthlyBudgetUsd;
        this.redisson = redisson;
        this.localDaily = redisson == null ? new ConcurrentHashMap<>() : null;
        this.localMonthly = redisson == null ? new ConcurrentHashMap<>() : null;
    }

    /** 仅用于测试：注入可控的本地 Map */
    CostLimiter(double perRequestBudget, double perDayBudget, double perMonthBudget,
                Map<String, DailyCost> daily, Map<String, MonthlyCost> monthly) {
        this.perRequestBudgetUsd = perRequestBudget;
        this.perUserDailyBudgetUsd = perDayBudget;
        this.perUserMonthlyBudgetUsd = perMonthBudget;
        this.redisson = null;
        this.localDaily = new ConcurrentHashMap<>(daily);
        this.localMonthly = new ConcurrentHashMap<>(monthly);
    }

    public void checkBeforeRequest(String userId, double estimatedCost) {
        if (estimatedCost <= 0) return;

        if (perRequestBudgetUsd > 0 && estimatedCost > perRequestBudgetUsd) {
            throw new CostLimitExceededException(estimatedCost, perRequestBudgetUsd, "per-request");
        }

        if (userId == null) return;

        if (perUserDailyBudgetUsd > 0) {
            if (redisson != null) {
                checkRedisBudget(dailyMap(), dailyKey(userId), perUserDailyBudgetUsd, estimatedCost, "daily");
            } else {
                DailyCost dc = localDaily.computeIfAbsent(userId, k -> new DailyCost());
                synchronized (dc) {
                    dc.ensureBucket();
                    checkLocalBudget(dc.accumulated, estimatedCost, perUserDailyBudgetUsd, "daily");
                }
            }
        }

        if (perUserMonthlyBudgetUsd > 0) {
            if (redisson != null) {
                checkRedisBudget(monthlyMap(), monthlyKey(userId), perUserMonthlyBudgetUsd, estimatedCost, "monthly");
            } else {
                MonthlyCost mc = localMonthly.computeIfAbsent(userId, k -> new MonthlyCost());
                synchronized (mc) {
                    mc.ensureBucket();
                    checkLocalBudget(mc.accumulated, estimatedCost, perUserMonthlyBudgetUsd, "monthly");
                }
            }
        }
    }

    public void recordCost(String userId, double actualCost) {
        if (userId == null || actualCost <= 0) return;

        if (perUserDailyBudgetUsd > 0) {
            if (redisson != null) {
                dailyMap().addAndGet(dailyKey(userId), actualCost);
            } else {
                DailyCost dc = localDaily.computeIfAbsent(userId, k -> new DailyCost());
                synchronized (dc) {
                    dc.ensureBucket();
                    dc.accumulated += actualCost;
                }
            }
        }

        if (perUserMonthlyBudgetUsd > 0) {
            if (redisson != null) {
                monthlyMap().addAndGet(monthlyKey(userId), actualCost);
            } else {
                MonthlyCost mc = localMonthly.computeIfAbsent(userId, k -> new MonthlyCost());
                synchronized (mc) {
                    mc.ensureBucket();
                    mc.accumulated += actualCost;
                }
            }
        }
    }

    public double getDailyCost(String userId) {
        if (redisson != null) {
            Double v = dailyMap().get(dailyKey(userId));
            return v != null ? v : 0;
        }
        DailyCost dc = localDaily.get(userId);
        if (dc == null) return 0;
        synchronized (dc) {
            dc.ensureBucket();
            return dc.accumulated;
        }
    }

    public double getMonthlyCost(String userId) {
        if (redisson != null) {
            Double v = monthlyMap().get(monthlyKey(userId));
            return v != null ? v : 0;
        }
        MonthlyCost mc = localMonthly.get(userId);
        if (mc == null) return 0;
        synchronized (mc) {
            mc.ensureBucket();
            return mc.accumulated;
        }
    }

    /** 获取租户总用量（按日维度聚合所有用户） */
    public double getTenantDailyUsage(String tenantId) {
        if (redisson == null) return localDaily.values().stream()
                .mapToDouble(d -> d.accumulated).sum();
        double total = 0;
        for (String key : getKeysByPattern(KEY_DAILY, tenantId)) {
            Double v = dailyMap().get(key);
            if (v != null) total += v;
        }
        return total;
    }

    /** 获取租户下指定用户的用量 */
    public double getUserDailyUsage(String tenantId, String userId) {
        return getDailyCost(userId);
    }

    private java.util.Set<String> getKeysByPattern(String mapName, String prefix) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (Object obj : redisson.getMap(mapName, JsonJacksonCodec.INSTANCE).keySet()) {
            String key = obj.toString();
            if (key.startsWith(prefix + ":")) {
                result.add(key);
            }
        }
        return result;
    }

    public void reset(String userId) {
        if (redisson != null) {
            dailyMap().remove(dailyKey(userId));
            monthlyMap().remove(monthlyKey(userId));
        } else {
            localDaily.remove(userId);
            localMonthly.remove(userId);
        }
    }

    // -- internal helpers --

    private void checkRedisBudget(RMap<String, Double> map, String key,
                                   double budget, double estimated, String type) {
        Double current = map.get(key);
        double projected = (current != null ? current : 0) + estimated;
        if (projected > budget) {
            throw new CostLimitExceededException(projected, budget, type);
        }
    }

    private void checkLocalBudget(double accumulated, double estimated, double budget, String type) {
        double projected = accumulated + estimated;
        if (projected > budget) {
            throw new CostLimitExceededException(projected, budget, type);
        }
    }

    private String dailyKey(String userId) {
        return userId + ":" + DAY_FMT.format(Instant.now().atZone(ZoneOffset.UTC));
    }

    private String monthlyKey(String userId) {
        return userId + ":" + MONTH_FMT.format(Instant.now().atZone(ZoneOffset.UTC));
    }

    private RMap<String, Double> dailyMap() {
        return redisson.getMap(KEY_DAILY, JsonJacksonCodec.INSTANCE);
    }

    private RMap<String, Double> monthlyMap() {
        return redisson.getMap(KEY_MONTHLY, JsonJacksonCodec.INSTANCE);
    }

    // -- local tracking classes (package-private for tests) --

    static class DailyCost {
        long bucketDay = -1;
        double accumulated;

        void ensureBucket() {
            long today = Instant.now().toEpochMilli() / 86_400_000;
            if (bucketDay != today) {
                bucketDay = today;
                accumulated = 0;
            }
        }
    }

    static class MonthlyCost {
        long bucketMonth = -1;
        double accumulated;

        void ensureBucket() {
            long month = Instant.now().toEpochMilli() / 2_629_746_000L;
            if (bucketMonth != month) {
                bucketMonth = month;
                accumulated = 0;
            }
        }
    }
}
