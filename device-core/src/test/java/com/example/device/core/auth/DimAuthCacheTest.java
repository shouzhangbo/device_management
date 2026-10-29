package com.example.device.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.device.core.dim.DimType;
import com.example.device.core.entity.DeviceDimSourceAuthDO;
import com.example.device.core.mapper.DeviceDimSourceAuthMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 授权快照的加载与失败保留策略。
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
class DimAuthCacheTest {

    private final DeviceDimSourceAuthMapper mapper = mock(DeviceDimSourceAuthMapper.class);

    @Test
    void loadsEnabledRowsAndIgnoresUnknownDim() {
        when(mapper.selectAllEnabled()).thenReturn(List.of(
                row("FACTORY", "SRC_A"), row("FACTORY", "SRC_B"), row("ONLINE", "SRC_A"), row("NOT_A_DIM", "X")));
        DimAuthCache cache = new DimAuthCache(mapper);

        cache.loadOnStartup();

        assertEquals(2, cache.authorizedDimCount());
        assertTrue(cache.snapshot().get(DimType.FACTORY).containsAll(List.of("SRC_A", "SRC_B")));
        assertTrue(cache.snapshot().get(DimType.ONLINE).contains("SRC_A"));
    }

    @Test
    void startupFailsWhenTableCannotBeRead() {
        when(mapper.selectAllEnabled()).thenThrow(new IllegalStateException("db down"));
        DimAuthCache cache = new DimAuthCache(mapper);

        assertThrows(UnauthenticatedRefreshedException.class, cache::loadOnStartup);
    }

    @Test
    void refreshFailureKeepsPreviousSnapshot() {
        when(mapper.selectAllEnabled()).thenReturn(List.of(row("BIND", "SRC_A")));
        DimAuthCache cache = new DimAuthCache(mapper);
        cache.loadOnStartup();

        when(mapper.selectAllEnabled()).thenThrow(new IllegalStateException("db down"));
        cache.refresh();

        assertEquals(1, cache.authorizedDimCount());
        assertTrue(cache.snapshot().get(DimType.BIND).contains("SRC_A"));
    }

    @Test
    void emptyRefreshDoesNotWipeSnapshot() {
        when(mapper.selectAllEnabled()).thenReturn(List.of(row("RECOVER", "SRC_A")));
        DimAuthCache cache = new DimAuthCache(mapper);
        cache.loadOnStartup();

        when(mapper.selectAllEnabled()).thenReturn(List.of());
        cache.refresh();

        assertEquals(1, cache.authorizedDimCount());
    }

    private static DeviceDimSourceAuthDO row(String dim, String source) {
        DeviceDimSourceAuthDO row = new DeviceDimSourceAuthDO();
        row.setDimType(dim);
        row.setSource(source);
        row.setEnabled(1);
        return row;
    }
}
