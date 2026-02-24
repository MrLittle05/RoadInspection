import { Filter, List, Plus } from "lucide-react";
import React from "react";
import { Virtuoso } from "react-virtuoso";
import { InspectionTask } from "../types";
import { PullToRefresh, RefreshResult } from "./PullToRefresh";
import { TaskCard } from "./TaskCard";

interface TaskListViewProps {
  tasks: InspectionTask[];
  onTaskClick: (task: InspectionTask) => void;
  onSyncStatusClick: (status: number) => void;
  onCreateClick: () => void;
  onRefresh: () => Promise<void>;
  isRefreshing: boolean;
  refreshResult?: RefreshResult | null;
}

export const TaskListView: React.FC<TaskListViewProps> = ({
  tasks,
  onTaskClick,
  onSyncStatusClick,
  onCreateClick,
  onRefresh,
  isRefreshing,
  refreshResult,
}) => {
  return (
    <>
      <PullToRefresh
        onRefresh={onRefresh}
        isRefreshing={isRefreshing}
        refreshResult={refreshResult}
      >
        <div className="p-4 pb-20 max-w-lg mx-auto w-full">
          <div className="flex items-center justify-between mb-3 text-xs text-slate-500 font-medium uppercase tracking-wider">
            <span>任务列表</span>
            <div className="flex items-center space-x-1">
              <Filter className="w-3 h-3" />
              <span>最近</span>
            </div>
          </div>

          {tasks.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20 text-slate-400">
              <List className="w-12 h-12 mb-2 opacity-20" />
              <p>未找到任务</p>
              <button
                onClick={onRefresh}
                className="mt-4 text-blue-500 text-sm"
              >
                点此刷新
              </button>
            </div>
          ) : (
            <Virtuoso
              useWindowScroll
              overscan={300}
              data={tasks}
              itemContent={(index, task) => (
                <TaskCard
                  key={task.taskId}
                  task={task}
                  onTaskClick={onTaskClick}
                  onSyncStatusClick={onSyncStatusClick}
                />
              )}
            />
          )}
        </div>
      </PullToRefresh>

      <button
        onClick={onCreateClick}
        className="fixed bottom-6 right-6 w-14 h-14 bg-blue-600 text-white rounded-full shadow-lg shadow-blue-600/30 flex items-center justify-center active:scale-95 transition-transform hover:bg-blue-700 z-40"
      >
        <Plus className="w-6 h-6" />
      </button>
    </>
  );
};
