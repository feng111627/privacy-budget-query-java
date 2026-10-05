package com.github.feng111627.privacybudgetqueryjava.data;

import com.github.feng111627.privacybudgetqueryjava.model.DataRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地演示数据集（固定、确定性，便于结论复现）。
 *
 * 维度字段：region（north/south/east/west）、age_band；
 * 度量字段：income、spend。
 *
 * west 只有 2 行，低于默认最小分组样本量 5，用于演示分组抑制。
 */
@Component
public class Dataset {

    private final List<DataRow> rows;

    public Dataset() {
        List<DataRow> data = new ArrayList<>();
        add(data, "north", "20-29", 5200, 2100);
        add(data, "north", "30-39", 6100, 2600);
        add(data, "north", "30-39", 6400, 2800);
        add(data, "north", "40-49", 7200, 3100);
        add(data, "north", "40-49", 7100, 3000);
        add(data, "north", "40-49", 7600, 3300);
        add(data, "north", "50-59", 8300, 3700);
        add(data, "north", "50-59", 8100, 3500);
        add(data, "south", "20-29", 4800, 1900);
        add(data, "south", "20-29", 4950, 2050);
        add(data, "south", "30-39", 5900, 2500);
        add(data, "south", "30-39", 6050, 2450);
        add(data, "south", "30-39", 6200, 2700);
        add(data, "south", "40-49", 6900, 3000);
        add(data, "south", "40-49", 7050, 3200);
        add(data, "south", "50-59", 7800, 3400);
        add(data, "east", "20-29", 5100, 2200);
        add(data, "east", "30-39", 6300, 2750);
        add(data, "east", "30-39", 6500, 2900);
        add(data, "east", "40-49", 7400, 3250);
        add(data, "east", "40-49", 7550, 3400);
        add(data, "east", "40-49", 7700, 3500);
        add(data, "east", "50-59", 8600, 3900);
        add(data, "west", "30-39", 5800, 2300);
        add(data, "west", "40-49", 6700, 2900);
        // 额外的 north/south 行，使这两个大区样本更充足
        add(data, "north", "30-39", 6250, 2650);
        add(data, "north", "50-59", 8450, 3850);
        add(data, "south", "20-29", 4700, 1850);
        add(data, "south", "40-49", 7000, 3100);
        add(data, "east", "50-59", 8800, 4000);
        this.rows = List.copyOf(data);
    }

    private static void add(List<DataRow> data, String region, String ageBand,
                            double income, double spend) {
        data.add(new DataRow(
                Map.of("region", region, "age_band", ageBand),
                Map.of("income", income, "spend", spend)));
    }

    public List<DataRow> rows() {
        return rows;
    }
}
