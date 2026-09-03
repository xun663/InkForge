package com.inkforge.planning.web;

import com.inkforge.common.NotFoundException;
import com.inkforge.planning.ContinuationMode;
import com.inkforge.planning.PlanDirection;
import com.inkforge.planning.PlanningService;
import com.inkforge.planning.StoryPlan;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 剧情规划 API（P6 续写模式）。Controller 只做参数接收与结果返回，业务在 PlanningService。
 *
 * <p>端点：
 * <ul>
 *   <li>POST /continuations/options —— 生成候选方向。mode=PLOT_CHOICE/EXPANSION 返回
 *       {@code List<PlanDirection>}（JSON 数组，临时数据不持久化）；mode=ENDING 返回
 *       {@link StoryPlan}（DRAFT，同时 upsert PlotThread）。响应形态随 mode而定，前端按 mode 分派。</li>
 *   <li>POST /continuations/plan —— 用户选定方向 → StoryPlan(DRAFT)</li>
 *   <li>GET /plans、GET /plans/{planId} —— 查询</li>
 *   <li>POST /plans/{planId}/confirm|complete|abandon —— 状态推进</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/novels/{novelId}")
public class PlanningController {

    private final PlanningService planningService;

    public PlanningController(PlanningService planningService) {
        this.planningService = planningService;
    }

    @PostMapping("/continuations/options")
    public Object options(@PathVariable String novelId,
                          @RequestBody(required = false) OptionsRequest request) {
        ContinuationMode mode = ContinuationMode.parse(request == null ? null : request.mode());
        String instruction = request == null ? null : request.userInstruction();
        if (mode == ContinuationMode.ENDING) {
            return planningService.createEndingPlan(novelId, instruction);
        }
        return planningService.proposeDirections(novelId, mode, instruction);
    }

    @PostMapping("/continuations/plan")
    public StoryPlan createPlan(@PathVariable String novelId,
                                @RequestBody(required = false) PlanFromDirectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        ContinuationMode mode = ContinuationMode.parse(request.mode());
        return planningService.createPlanFromDirection(novelId, mode, request.direction(), request.userInstruction());
    }

    @GetMapping("/plans")
    public List<StoryPlan> list(@PathVariable String novelId) {
        return planningService.list(novelId);
    }

    @GetMapping("/plans/{planId}")
    public StoryPlan get(@PathVariable String novelId, @PathVariable String planId) {
        return planningService.get(novelId, planId);
    }

    @PostMapping("/plans/{planId}/confirm")
    public StoryPlan confirm(@PathVariable String novelId, @PathVariable String planId) {
        return planningService.confirm(novelId, planId);
    }

    @PostMapping("/plans/{planId}/complete")
    public StoryPlan complete(@PathVariable String novelId, @PathVariable String planId) {
        return planningService.complete(novelId, planId);
    }

    @PostMapping("/plans/{planId}/abandon")
    public StoryPlan abandon(@PathVariable String novelId, @PathVariable String planId) {
        return planningService.abandon(novelId, planId);
    }

    /** mode 必填：PLOT_CHOICE / ENDING / EXPANSION。 */
    public record OptionsRequest(String mode, String userInstruction) {
    }

    public record PlanFromDirectionRequest(String mode, PlanDirection direction, String userInstruction) {
    }
}
