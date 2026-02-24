import React, { useState, useMemo } from "react";
import { InspectionTask, InspectionRecord } from "../types";
import { StatsChart } from "./StatsChart";
import { RecordItem } from "./RecordItem";
import { Virtuoso } from "react-virtuoso";
import { ArrowUpDown } from "lucide-react";
import { PullToRefresh, RefreshResult } from "./PullToRefresh";

interface TaskDetailViewProps {
  task: InspectionTask;
  records: InspectionRecord[];
  onImageClick: (url: string) => void;
  onRefresh: () => Promise<void>;
  isRefreshing: boolean;
  refreshResult?: RefreshResult | null;
}

export const TaskDetailView: React.FC<TaskDetailViewProps> = ({
  task,
  records,
  onImageClick,
  onRefresh,
  isRefreshing,
  refreshResult,
}) => {
  const [sortOrder, setSortOrder] = useState<"latest" | "earliest">("latest");

  // Derived stats
  const distressCount = records.filter(
    (r) => r.pavementDistress && r.pavementDistress.length > 0,
  ).length;

  const avgIri =
    records.length > 0
      ? (records.reduce((acc, r) => acc + r.iri, 0) / records.length).toFixed(1)
      : "0.0";

  const sortedRecords = useMemo(() => {
    return [...records].sort((a, b) => {
      if (sortOrder === "latest") {
        return b.captureTime - a.captureTime;
      } else {
        return a.captureTime - b.captureTime;
      }
    });
  }, [records, sortOrder]);

  const handleResume = () => {
    if (window.AndroidNative) {
      window.AndroidNative.startInspectionActivity(
        "inspection.html",
        task.taskId,
      );
    } else {
      console.log("Mock Resume: ", task.taskId);
    }
  };

  return (
    <PullToRefresh
      onRefresh={onRefresh}
      isRefreshing={isRefreshing}
      refreshResult={refreshResult}
    >
      <div className="p-4 pb-20 max-w-lg mx-auto w-full">
        {/* KPI Cards */}
        <div className="grid grid-cols-3 gap-3 mb-4">
          <div className="bg-white p-3 rounded-lg border border-slate-100 shadow-sm flex flex-col items-center">
            <span className="text-xs text-slate-400 uppercase">记录数</span>
            <span className="text-lg font-bold text-slate-800">
              {records.length}
            </span>
          </div>
          <div className="bg-white p-3 rounded-lg border border-slate-100 shadow-sm flex flex-col items-center">
            <span className="text-xs text-slate-400 uppercase">平均 IRI</span>
            <span
              className={`text-lg font-bold ${Number(avgIri) > 4 ? "text-red-600" : "text-slate-800"}`}
            >
              {avgIri}
            </span>
          </div>
          <div className="bg-white p-3 rounded-lg border border-slate-100 shadow-sm flex flex-col items-center">
            <span className="text-xs text-slate-400 uppercase">病害数</span>
            <span className="text-lg font-bold text-slate-800">
              {distressCount}
            </span>
          </div>
        </div>

        <StatsChart records={records} />

        <div className="flex items-center justify-between mb-3 mt-6">
          <h2 className="font-bold text-slate-800">巡检日志</h2>
          <button
            onClick={() =>
              setSortOrder((prev) =>
                prev === "latest" ? "earliest" : "latest",
              )
            }
            className="flex items-center space-x-1 text-xs font-medium text-blue-600 bg-blue-50 hover:bg-blue-100 px-3 py-1.5 rounded-full transition-colors active:scale-95"
          >
            <ArrowUpDown className="w-3.5 h-3.5" />
            <span>{sortOrder === "latest" ? "最新优先" : "最早优先"}</span>
          </button>
        </div>

        <Virtuoso
          useWindowScroll
          data={sortedRecords}
          itemContent={(index, record) => (
            <RecordItem
              key={record.id}
              record={record}
              onImageClick={onImageClick}
            />
          )}
        />

        {/* Bottom Sticky Action Bar (If task is not finished) */}
        {!task.isFinished && (
          <div className="fixed bottom-0 left-0 right-0 p-4 bg-white border-t border-slate-200">
            <div className="max-w-lg mx-auto flex gap-3">
              <button className="flex-1 bg-slate-100 text-slate-700 py-3 rounded-lg font-semibold text-sm">
                暂停
              </button>
              <button
                onClick={handleResume}
                className="flex-1 bg-blue-600 text-white py-3 rounded-lg font-semibold text-sm shadow-lg shadow-blue-500/30"
              >
                继续巡检
              </button>
            </div>
          </div>
        )}
      </div>
    </PullToRefresh>
  );
};
