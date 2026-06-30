package com.jushen.digitaltwin.service;

import com.jushen.digitaltwin.config.SimulationProperties;
import com.jushen.digitaltwin.model.City;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class SimulationDataFactory {

    private final City headquarters;
    private final List<City> routeCities;
    private final List<String> cargoes;
    private final List<String> plates;

    public SimulationDataFactory(SimulationProperties properties) {
        // 总部
        this.headquarters = properties.getHeadquarters().toCity();

        // 城市列表
        this.routeCities = new ArrayList<>();
        for (SimulationProperties.CityConfig cfg : properties.getCities()) {
            routeCities.add(cfg.toCity());
        }
        // 确保总部也在路线城市中
        if (routeCities.stream().noneMatch(c -> c.name().equals(headquarters.name()))) {
            routeCities.add(headquarters);
        }

        // 货物和车牌，配置文件为空时使用默认值
        this.cargoes = (properties.getCargoes() != null && !properties.getCargoes().isEmpty())
                ? properties.getCargoes()
                : List.of("铝锭", "铜材", "钢材", "化工原料", "其他");

        this.plates = (properties.getPlates() != null && !properties.getPlates().isEmpty())
                ? properties.getPlates()
                : List.of("粤A·HQ7832", "湘E·LA4512", "赣C·TQ1129", "粤B·MD5521");
    }

    public Map<String, Object> nextKpiMessage() {
        Map<String, Object> message = baseMessage("kpi");
        message.put("revenue", decimal(96, 168));
        message.put("orderCount", integer(280, 520));
        message.put("completionRate", decimal(88, 99));
        message.put("punctualityRate", decimal(90, 99.8));
        return message;
    }

    public Map<String, Object> nextInventoryMessage() {
        Map<String, Object> message = baseMessage("inventory");
        message.put("total", integer(11800, 14500));
        message.put("todayIn", integer(260, 520));
        message.put("todayOut", integer(220, 480));
        message.put("categoryPie", List.of(
                category("铝锭", integer(2800, 3800)),
                category("铜材", integer(2300, 3300)),
                category("钢材", integer(3600, 4800)),
                category("化工原料", integer(1500, 2400)),
                category("其他", integer(700, 1300))
        ));
        message.put("hourlyTrend", trendRows());
        return message;
    }

    public Map<String, Object> nextVehicleMessage() {
        int inUse = integer(18, 36);
        int idle = integer(5, 16);
        Map<String, Object> message = baseMessage("vehicle");
        message.put("inUse", inUse);
        message.put("idle", idle);
        // 随机生成4条排队记录
        List<Map<String, Object>> queueList = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            queueList.add(queue(
                    randomPlate(),
                    randomCargo(),
                    randomTime(),
                    i == 0 ? "装载中" : "待装载"  // 第一条为装载中
            ));
        }
        message.put("queueList", queueList);
        return message;
    }

    public Map<String, Object> nextTrafficEnergyMessage() {
        List<String> times = rollingTimes();
        Map<String, Object> trafficData = new LinkedHashMap<>();
        trafficData.put("times", times);
        trafficData.put("in", randomSeries(45, 150, false));
        trafficData.put("out", randomSeries(35, 135, false));

        Map<String, Object> energyData = new LinkedHashMap<>();
        energyData.put("times", times);
        energyData.put("electricity", randomSeries(210, 430, false));
        energyData.put("water", randomSeries(5, 10, true));

        Map<String, Object> message = baseMessage("traffic_energy");
        message.put("trafficData", trafficData);
        message.put("energyData", energyData);
        return message;
    }

    public City randomCity() {
        return routeCities.get(integer(0, routeCities.size() - 1));
    }

    public City randomDifferentCity(City city) {
        City next = randomCity();
        while (next.equals(city)) {
            next = randomCity();
        }
        return next;
    }

    public int randomRouteDelaySeconds() {
        return integer(1, 3);
    }

    public double randomRouteValue() {
        return decimal(80, 580);
    }

    public Map<String, Object> cityRaiseMessage(String lineId, City from, City to) {
        Map<String, Object> message = baseRouteMessage("city_raise", lineId, from, to);
        message.put("fromCoords", coords(from));
        message.put("toCoords", coords(to));
        return message;
    }

    public Map<String, Object> cityFallMessage(String lineId, City from, City to) {
        return baseRouteMessage("city_fall", lineId, from, to);
    }

    private Map<String, Object> baseMessage(String type) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        return message;
    }

    private Map<String, Object> baseRouteMessage(String type, String lineId, City from, City to) {
        Map<String, Object> message = baseMessage(type);
        message.put("from", from.name());
        message.put("to", to.name());
        message.put("lineId", lineId);
        return message;
    }

    private double[] coords(City city) {
        return new double[] {city.lng(), city.lat()};
    }

    private Map<String, Object> category(String name, int value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("value", value);
        return row;
    }

    private Map<String, Object> queue(String plate, String cargo, String estimated, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("plate", plate);
        row.put("cargo", cargo);
        row.put("estimated", estimated);
        row.put("status", status);
        return row;
    }

    private List<Map<String, Object>> trendRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> times = rollingTimes();
        for (String time : times) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", time);
            row.put("in", integer(35, 95));
            row.put("out", integer(30, 88));
            rows.add(row);
        }
        return rows;
    }

    private List<String> rollingTimes() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        LocalTime now = LocalTime.now().withSecond(0).withNano(0);
        List<String> times = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            times.add(now.minusMinutes(i * 10L).format(formatter));
        }
        return times;
    }

    private List<Number> randomSeries(int min, int max, boolean decimal) {
        List<Number> values = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            values.add(decimal ? decimal(min, max) : integer(min, max));
        }
        return values;
    }

    private int integer(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private double decimal(double min, double max) {
        double value = ThreadLocalRandom.current().nextDouble(min, max);
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private String randomPlate() {
        return plates.get(integer(0, plates.size() - 1));
    }

    private String randomCargo() {
        return cargoes.get(integer(0, cargoes.size() - 1));
    }

    private String randomTime() {
        LocalTime now = LocalTime.now().plusMinutes(integer(5, 60));
        return now.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /**
     * 生成两点间的直线插值路径（模拟道路）
     * @param fromLng 起点经度
     * @param fromLat 起点纬度
     * @param toLng   终点经度
     * @param toLat   终点纬度
     * @param points  插值点数量（包含首尾）
     * @return 经纬度坐标数组
     */
    public List<double[]> simulateRoadPath(double fromLng, double fromLat, double toLng, double toLat, int points) {
        List<double[]> path = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            double t = i / (double)(points - 1);
            double lng = fromLng + (toLng - fromLng) * t;
            double lat = fromLat + (toLat - fromLat) * t;
            path.add(new double[]{lng, lat});
        }
        return path;
    }

    /**
     * 生成多段折线路径（依次连接每个航点）
     * @param waypoints 航点列表，每个航点是 [lng, lat] 数组
     * @param totalPoints 整条路径的插值点总数（包含首尾）
     * @return 合并后的经纬度坐标数组
     */
    public List<double[]> simulateMultiPointPath(List<double[]> waypoints, int totalPoints) {
        List<double[]> fullPath = new ArrayList<>();
        if (waypoints == null || waypoints.size() < 2 || totalPoints < 2) {
            return fullPath;
        }

        // 计算每段线段应分配的点数
        double[] segmentLengths = new double[waypoints.size() - 1];
        double totalLength = 0.0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            double[] start = waypoints.get(i);
            double[] end = waypoints.get(i + 1);
            double dx = end[0] - start[0];
            double dy = end[1] - start[1];
            segmentLengths[i] = Math.sqrt(dx * dx + dy * dy);
            totalLength += segmentLengths[i];
        }

        int remainingPoints = totalPoints - 1; // 去掉起点本身（起点在循环中添加）
        for (int i = 0; i < waypoints.size() - 1; i++) {
            double[] start = waypoints.get(i);
            double[] end = waypoints.get(i + 1);

            // 最后一个线段分配剩余所有点数，避免舍入误差
            int segmentPoints = (i == waypoints.size() - 2)
                    ? remainingPoints + 1  // 包含本段终点
                    : Math.max(2, (int) Math.round(segmentLengths[i] / totalLength * totalPoints));

            if (i > 0) {
                // 避免重复添加前一段的终点（本段起点）
                segmentPoints--;
            }

            List<double[]> segmentPath = simulateRoadPath(start[0], start[1], end[0], end[1], segmentPoints);
            // 如果不是第一段，跳过第一个点（因为前一段的终点已经添加）
            if (i > 0 && !segmentPath.isEmpty()) {
                segmentPath = segmentPath.subList(1, segmentPath.size());
            }
            fullPath.addAll(segmentPath);
            remainingPoints -= (segmentPath.size() - (i == 0 ? 0 : 1));
        }

        return fullPath;
    }
}