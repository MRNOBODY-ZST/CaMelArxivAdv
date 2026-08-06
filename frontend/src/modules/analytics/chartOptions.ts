import type { EChartsCoreOption } from 'echarts/core'

import type { Breakdown, DailyCount, FunnelStep, NamedCount } from '@/modules/analytics/analytics.types'

export const palette = ['#4f6ef7', '#22c55e', '#f59e0b', '#8b5cf6', '#06b6d4', '#ef4444', '#64748b']

const axis = { axisLine: { lineStyle: { color: '#cbd5e1' } }, axisLabel: { color: '#64748b', fontSize: 11 } }

export function countBars(data: NamedCount[], horizontal = false): EChartsCoreOption {
  const labels = data.map((item) => item.label)
  const values = data.map((item) => item.count)
  return {
    aria: { enabled: true }, color: palette,
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: horizontal ? 120 : 45, right: 18, top: 12, bottom: horizontal ? 24 : 70, containLabel: false },
    xAxis: horizontal
      ? { type: 'value', ...axis, splitLine: { lineStyle: { color: '#eef2f7' } } }
      : { type: 'category', data: labels, ...axis, axisLabel: { ...axis.axisLabel, rotate: labels.length > 6 ? 32 : 0 } },
    yAxis: horizontal
      ? { type: 'category', data: labels, ...axis, inverse: true, axisLabel: { ...axis.axisLabel, width: 105, overflow: 'truncate' } }
      : { type: 'value', ...axis, splitLine: { lineStyle: { color: '#eef2f7' } } },
    series: [{ type: 'bar', data: values, barMaxWidth: 34, itemStyle: { borderRadius: horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0] } }],
  }
}

export function dailyOption(data: DailyCount[] | NamedCount[]): EChartsCoreOption {
  const labels = data.map((item) => 'date' in item ? item.date : item.label)
  const values = data.map((item) => item.count)
  const line = data.length >= 8
  return {
    aria: { enabled: true }, color: palette,
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 18, top: 16, bottom: 62 },
    xAxis: { type: 'category', data: labels, boundaryGap: !line, ...axis, axisLabel: { ...axis.axisLabel, rotate: labels.length > 8 ? 35 : 0 } },
    yAxis: { type: 'value', minInterval: 1, ...axis, splitLine: { lineStyle: { color: '#eef2f7' } } },
    series: [{
      type: line ? 'line' : 'bar', data: values, smooth: false, symbol: 'circle', symbolSize: 6,
      areaStyle: line ? { color: 'rgba(79,110,247,.10)' } : undefined,
      itemStyle: { borderRadius: line ? 0 : [4, 4, 0, 0] }, barMaxWidth: 34,
    }],
  }
}

export function donutOption(data: NamedCount[]): EChartsCoreOption {
  return {
    aria: { enabled: true }, color: palette,
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} ({d}%)' },
    legend: { bottom: 0, type: 'scroll', textStyle: { color: '#64748b', fontSize: 11 } },
    series: [{
      type: 'pie', radius: ['45%', '70%'], center: ['50%', '43%'], avoidLabelOverlap: true,
      label: { show: false }, emphasis: { label: { show: true, fontWeight: 'bold' } },
      data: data.map((item) => ({ name: item.label, value: item.count })),
    }],
  }
}

export function funnelBars(data: FunnelStep[]): EChartsCoreOption {
  return countBars(data.map((item) => ({ key: item.key, label: item.label, count: item.count })), true)
}

export function rateBars(data: Breakdown[]): EChartsCoreOption {
  return {
    ...countBars(data.map((item) => ({ key: item.key, label: item.label, count: item.rate * 100 })), true),
    tooltip: {
      trigger: 'axis',
      formatter: (items: unknown) => {
        const first = Array.isArray(items) ? items[0] as { dataIndex?: number } : null
        const item = data[first?.dataIndex ?? 0]
        return item ? `${item.label}<br/>${(item.rate * 100).toFixed(1)}% (${item.numerator}/${item.denominator})` : ''
      },
    },
  }
}

export function durationBars(values: NamedCount[]): EChartsCoreOption {
  return countBars(values)
}
