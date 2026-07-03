package com.jushen.digitaltwin.grouping;

import java.util.List;

/**
 * 公共路线视图，供隔离分组策略使用。
 *
 * <p>策略层只依赖这个轻量接口，不依赖当前的调度服务、控制器或数据库模型。
 * 后续接入真实订单时，可以用适配器把业务对象转换成 RouteInfo。</p>
 */
public interface RouteInfo {
    String getLineId();

    String getFrom();

    String getTo();

    double[] getFromCoords();

    double[] getToCoords();

    double getRouteLengthKm();

    double getSpeedKmh();

    long getTravelDurationMs();

    long getStartTime();

    List<double[]> getCoordinates();
}
