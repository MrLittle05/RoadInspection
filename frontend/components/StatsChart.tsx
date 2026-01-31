import React from 'react';
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  ReferenceLine
} from 'recharts';
import { InspectionRecord } from '../types';

interface StatsChartProps {
  records: InspectionRecord[];
}

export const StatsChart: React.FC<StatsChartProps> = ({ records }) => {
  // Sort records by capture time to show progression
  const sortedRecords = [...records].sort((a, b) => a.captureTime - b.captureTime);
  
  const data = sortedRecords.map((r, index) => ({
    name: index + 1, // Just using index for sequence
    iri: r.iri,
    time: new Date(r.captureTime).toLocaleTimeString('zh-CN'),
  }));

  const averageIri = records.length > 0 
    ? records.reduce((acc, curr) => acc + curr.iri, 0) / records.length 
    : 0;

  return (
    <div className="w-full bg-white rounded-xl p-4 shadow-sm border border-slate-100 mb-4">
        <div className="flex justify-between items-center mb-4">
            <h4 className="text-sm font-semibold text-slate-700">平整度分布 (IRI)</h4>
            <div className="text-xs text-slate-500">
                平均值: <span className={`font-bold ${averageIri > 4 ? 'text-red-600' : 'text-slate-800'}`}>{averageIri.toFixed(2)}</span>
            </div>
        </div>
      {/* Explicit height wrapper for Recharts to prevent width(-1)/height(-1) errors */}
      <div className="w-full h-32">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart
            data={data}
            margin={{
              top: 5,
              right: 0,
              left: -20,
              bottom: 0,
            }}
          >
            <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
            <XAxis dataKey="name" tick={{fontSize: 10}} tickLine={false} axisLine={false} />
            <YAxis tick={{fontSize: 10}} tickLine={false} axisLine={false} domain={[0, 10]} />
            <Tooltip 
              contentStyle={{ borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }}
              itemStyle={{ fontSize: '12px', color: '#1e293b' }}
              labelStyle={{ display: 'none' }}
            />
            <ReferenceLine y={4} stroke="#ef4444" strokeDasharray="3 3" />
            <ReferenceLine y={2} stroke="#22c55e" strokeDasharray="3 3" />
            <Area 
              type="monotone" 
              dataKey="iri" 
              stroke="#3b82f6" 
              fill="#eff6ff" 
              strokeWidth={2}
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
};