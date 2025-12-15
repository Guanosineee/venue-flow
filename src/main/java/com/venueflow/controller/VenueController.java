package com.venueflow.controller;

import com.venueflow.dto.Result;
import com.venueflow.entity.Venue;
import com.venueflow.service.VenueService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/venue")
public class VenueController {

    private final VenueService venueService;

    public VenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    /** 展馆详情（缓存三兄弟主战场：策略由 venue-flow.cache-mode 决定） */
    @GetMapping("/{id}")
    public Result<Venue> byId(@PathVariable Long id) {
        return Result.ok(venueService.queryById(id));
    }

    /** 附近展馆：GEO。例：/venue/nearby?lon=116.40&lat=39.90&radius=5 */
    @GetMapping("/nearby")
    public Result<List<Map<String, Object>>> nearby(@RequestParam double lon,
                                                    @RequestParam double lat,
                                                    @RequestParam(defaultValue = "5") double radius) {
        return Result.ok(venueService.nearby(lon, lat, radius));
    }

    /** 全量坐标载入 Redis GEO（种子数据后跑一次即可） */
    @PostMapping("/geo/load")
    public Result<Void> loadGeo() {
        venueService.loadGeo();
        return Result.ok();
    }

    /** 逻辑过期模式的预热（压测前把热点展馆塞进缓存） */
    @PostMapping("/preload/{id}")
    public Result<Void> preload(@PathVariable Long id) {
        venueService.preload(id);
        return Result.ok();
    }

    /** 清缓存（演示 Cache-Aside 的"改库后删缓存"） */
    @DeleteMapping("/cache/{id}")
    public Result<Void> evict(@PathVariable Long id) {
        venueService.evict(id);
        return Result.ok();
    }
}
