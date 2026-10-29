package com.example.device.core.mapper;

import com.example.device.core.entity.DeviceDimSourceAuthDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 维度权威源授权配置的持久层（EP-012）。
 * <p>
 * 全量一次读：表行数 = 维度 × source，量级 ≤ 数十，因此不做分页也不做增量。
 * 消息热路径<b>禁止</b>调用本接口，读的是内存快照。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
@Mapper
public interface DeviceDimSourceAuthMapper {

    /**
     * 读取全部启用的授权行。
     *
     * @return 授权行列表，{@code enabled = 0} 的行不在结果内
     */
    List<DeviceDimSourceAuthDO> selectAllEnabled();
}
