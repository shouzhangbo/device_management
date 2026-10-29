/**
 * 设备管理唯一模型层。
 * <p>
 * 实体、Mapper、维度白名单（{@code DimSpec}）、事务入口（{@code DeviceInfoApplier}）、缓存与读服务、
 * 对外响应公共件全部只在本包定义；{@code device-ingest} / {@code device-openapi} / {@code device-admin}
 * 只能单向依赖本模块，禁止各自建模或自建事务入口（{@code tech-design} §4.7 TD-006）。
 * </p>
 *
 * @author houzhangbo
 * @since 2026-10-29
 */
package com.example.device.core;
