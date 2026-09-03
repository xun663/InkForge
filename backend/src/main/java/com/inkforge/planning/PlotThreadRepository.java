package com.inkforge.planning;

import java.util.List;
import java.util.Optional;

/** PlotThread 仓储端口（域层）。title 参数一律传 {@link PlotThread#normalized(String)} 结果。 */
public interface PlotThreadRepository {

    PlotThread save(PlotThread thread);

    Optional<PlotThread> findById(String id);

    Optional<PlotThread> findByTitle(String novelId, String normalizedTitle);

    List<PlotThread> findByNovelId(String novelId);

    List<PlotThread> findOpenByNovelId(String novelId);
}
