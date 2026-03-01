import React, { useEffect, useMemo, useRef } from 'react'
import {
  Area,
  AreaChart,
  CartesianGrid,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { InspectionRecord } from '../types'

interface StatsChartProps {
  records: InspectionRecord[]
  onChartClick?: (recordId: number) => void
}

export const StatsChart: React.FC<StatsChartProps> = ({
  records,
  onChartClick,
}) => {
  // 1. 新增一個 ref 用來控制滾動條位置
  const scrollContainerRef = useRef<HTMLDivElement>(null)

  const sortedRecords = [...records].sort(
    (a, b) => a.captureTime - b.captureTime,
  )

  const data = sortedRecords
    .filter((r) => r.iri !== null && r.iri !== undefined && r.iri >= 0)
    .map((r, index) => ({
      id: r.id,
      name: index + 1,
      iri: r.iri,
      time: new Date(r.captureTime).toLocaleTimeString('zh-CN'),
    }))

  const avgIri = useMemo(() => {
    const validIris = records
      .map((r) => r.iri)
      .filter(
        (iri): iri is number => iri !== null && iri !== undefined && iri > 0,
      )

    if (validIris.length === 0) return 0.0

    const sum = validIris.reduce((acc, val) => acc + val, 0)
    return Number((sum / validIris.length).toFixed(2))
  }, [records])

  // 2. 當數據更新時，自動將滾動條拉到最右側（顯示最新數據）
  useEffect(() => {
    if (scrollContainerRef.current) {
      scrollContainerRef.current.scrollLeft =
        scrollContainerRef.current.scrollWidth
    }
  }, [data.length])

  // 3. 動態計算內部圖表的寬度。
  // 假設每個點需要 40px 的寬度，這樣就能保證在視窗內顯示的數量是固定的。
  // 如果數據太少，則默認撐滿父容器 (100%)。
  const chartWidth = Math.max(
    data.length * 20,
    scrollContainerRef.current?.clientWidth || 0,
  )

  return (
    <div className='w-full bg-white rounded-xl p-4 shadow-sm border border-slate-100 mb-4'>
      <div className='flex justify-between items-center mb-4'>
        <h4 className='text-sm font-semibold text-slate-700'>平整度 (IRI)</h4>
        <div className='text-xs text-slate-500'>
          平均值:{' '}
          <span
            className={`font-bold ${avgIri > 4 ? 'text-red-600' : 'text-slate-800'}`}
          >
            {avgIri}
          </span>
        </div>
      </div>

      {/* 4. 外層容器：設定 overflow-x-auto 允許水平滑動，並隱藏原生醜陋的滾動條（可選） */}
      <div
        ref={scrollContainerRef}
        className='w-full h-48 overflow-x-auto overflow-y-hidden smooth-scroll'
        style={{
          scrollbarWidth: 'none',
          msOverflowStyle: 'none',
          WebkitTapHighlightColor: 'transparent',
        }}
      >
        {/* Webkit 隱藏滾動條可以透過 global CSS 或加上自定義 class */}
        {/* 5. 內層容器：寬度由數據量決定，數據越多越長 */}
        <div
          className='[&_*]:!outline-none [&_*]:focus:!outline-none'
          style={{
            width: chartWidth > 0 ? `${chartWidth}px` : '100%',
            minWidth: '100%',
            height: '100%',
          }}
        >
          <ResponsiveContainer width='100%' height='100%'>
            <AreaChart
              data={data}
              margin={{ top: 5, right: 20, left: -20, bottom: 0 }}
              style={{ outline: 'none' }}
              onMouseUp={(e: any) => {
                if (e && e.activePayload && e.activePayload.length > 0) {
                  const clickedData = e.activePayload[0].payload
                  if (clickedData && clickedData.id && onChartClick) {
                    onChartClick(clickedData.id)
                  }
                }
              }}
            >
              <CartesianGrid
                strokeDasharray='3 3'
                vertical={false}
                stroke='#f1f5f9'
              />
              <XAxis
                dataKey='name'
                tick={{ fontSize: 10 }}
                tickLine={false}
                axisLine={false}
                minTickGap={20}
              />
              <YAxis
                tick={{ fontSize: 10 }}
                tickLine={false}
                axisLine={false}
                domain={[0, 10]}
              />
              <Tooltip
                contentStyle={{
                  borderRadius: '8px',
                  border: 'none',
                  boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)',
                }}
                itemStyle={{ fontSize: '12px', color: '#1e293b' }}
                labelStyle={{
                  color: '#64748b',
                  marginBottom: '4px',
                  fontSize: '12px',
                }}
              />
              <ReferenceLine y={4} stroke='#ef4444' strokeDasharray='3 3' />
              <ReferenceLine y={2} stroke='#22c55e' strokeDasharray='3 3' />
              <Area
                type='monotone'
                dataKey='iri'
                stroke='#3b82f6'
                fill='#eff6ff'
                strokeWidth={2}
                isAnimationActive={false} // 建議關閉動畫，否則動態增加數據時畫面會閃爍
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
  )
}
