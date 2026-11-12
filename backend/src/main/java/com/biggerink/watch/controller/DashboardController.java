package com.biggerink.watch.controller;

import com.biggerink.watch.common.ApiDtos;
import com.biggerink.watch.common.R;
import com.biggerink.watch.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/overview")
    public R<ApiDtos.DashboardOverviewVO> overview() {
        return R.ok(dashboardService.overview());
    }

    @GetMapping("/trend")
    public R<List<ApiDtos.TrendPointVO>> trend(
            @RequestParam(defaultValue = "7") int days) {
        return R.ok(dashboardService.trend(days));
    }

    @GetMapping("/realtime")
    public R<Map<String, Object>> realtime() {
        return R.ok(dashboardService.realtime());
    }
}
