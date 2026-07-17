package com.jushen.digitaltwin.townroad;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DailyOrderStatisticsController {

    private final DailyOrderStatisticsService statisticsService;

    public DailyOrderStatisticsController(DailyOrderStatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/daily-kpis")
    public DailyOrderStatisticsService.DailyOrderStatistics dailyKpis() {
        return statisticsService.snapshot();
    }
}
