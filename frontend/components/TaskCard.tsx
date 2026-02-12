import React, { useState, useRef, useEffect } from "react";
import { InspectionTask } from "../types";
import {
  Calendar,
  MapPin,
  CheckCircle2,
  Clock,
  Cloud,
  CloudOff,
  CloudUpload,
  Trash2,
  AlertCircle,
} from "lucide-react";

interface TaskCardProps {
  task: InspectionTask;
  onTaskClick: (task: InspectionTask) => void;
  onSyncStatusClick: (status: number) => void;
}

export const TaskCard: React.FC<TaskCardProps> = ({
  task,
  onTaskClick,
  onSyncStatusClick,
}) => {
  const [swipeOffset, setSwipeOffset] = useState(0);
  const [isSwiping, setIsSwiping] = useState(false);
  const [isOpen, setIsOpen] = useState(false);
  const [isConfirming, setIsConfirming] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);

  const startX = useRef(0);
  const currentX = useRef(0);
  const deleteBtnWidth = 80;

  // 当滑动关闭时，重置确认状态
  useEffect(() => {
    if (!isOpen) {
      setIsConfirming(false);
    }
  }, [isOpen]);

  // --- 触摸事件处理 (仅保留移动端逻辑) ---
  const handleTouchStart = (e: React.TouchEvent) => {
    if (isDeleting) return;
    startX.current = e.touches[0].clientX;
    setIsSwiping(true);
  };

  const handleTouchMove = (e: React.TouchEvent) => {
    if (!isSwiping || isDeleting) return;
    currentX.current = e.touches[0].clientX;
    const diff = currentX.current - startX.current;

    if (isOpen) {
      // 如果已经打开，允许向右滑回去
      const newOffset = -deleteBtnWidth + diff;
      setSwipeOffset(Math.min(0, newOffset));
    } else if (diff < 0) {
      // 如果是关闭状态，仅允许向左滑
      setSwipeOffset(Math.max(-deleteBtnWidth - 20, diff));
    }
  };

  const handleTouchEnd = () => {
    if (isDeleting) return;
    setIsSwiping(false);

    // 阈值判断：滑过按钮宽度的一半则自动展开
    if (swipeOffset < -deleteBtnWidth / 2) {
      setSwipeOffset(-deleteBtnWidth);
      setIsOpen(true);
    } else {
      setSwipeOffset(0);
      setIsOpen(false);
    }
  };

  const handleDeleteClick = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();

    if (!isConfirming) {
      // 第一步：进入确认状态 (变橙色 + 弹跳图标)
      setIsConfirming(true);
    } else {
      // 第二步：确认删除

      // 1. 执行视觉删除动画 (卡片收缩消失)
      setIsDeleting(true);

      const safeTaskId = String(task.taskId);
      console.log("正在请求原生删除任务: " + safeTaskId);

      // 2. 调用安卓原生接口
      if (window.AndroidNative && window.AndroidNative.deleteTask) {
        window.AndroidNative.deleteTask(safeTaskId);
      } else {
        console.warn("未检测到 AndroidNative 接口，无法执行物理删除");
      }
    }
  };

  const handleCardClick = (e: React.MouseEvent) => {
    // 如果侧滑菜单打开中，点击卡片任何位置都视为“关闭菜单”
    if (isOpen) {
      e.preventDefault();
      setSwipeOffset(0);
      setIsOpen(false);
      setIsConfirming(false);
    } else if (!isDeleting) {
      // 正常跳转
      onTaskClick(task);
    }
  };

  const handleSyncClick = (e: React.MouseEvent, state: number) => {
    e.preventDefault();
    e.stopPropagation();
    if (!isOpen) {
      onSyncStatusClick(state);
    }
  };

  const getSyncIcon = (state: number) => {
    const iconSize = "w-7 h-7";
    const btnClass = "p-2 -mr-2 rounded-full transition-colors active:scale-95";

    switch (state) {
      case 0:
        return (
          <button
            onClick={(e) => handleSyncClick(e, state)}
            className={`${btnClass} hover:bg-slate-100 text-slate-400`}
            title="本地新建 (未同步)"
          >
            <CloudUpload className={iconSize} strokeWidth={1.5} />
          </button>
        );
      case 1:
        return (
          <button
            onClick={(e) => handleSyncClick(e, state)}
            className={`${btnClass} hover:bg-blue-50 text-blue-500`}
            title="已同步 (进行中)"
          >
            <Cloud className={iconSize} strokeWidth={1.5} />
          </button>
        );
      case 2:
        return (
          <button
            onClick={(e) => handleSyncClick(e, state)}
            className={`${btnClass} hover:bg-green-50 text-green-500`}
            title="已归档 (完成)"
          >
            <Cloud className={iconSize} strokeWidth={1.5} />
          </button>
        );
      default:
        return (
          <button
            onClick={(e) => handleSyncClick(e, state)}
            className={`${btnClass} hover:bg-red-50 text-red-500`}
            title="同步状态未知"
          >
            <CloudOff className={iconSize} strokeWidth={1.5} />
          </button>
        );
    }
  };

  return (
    <div
      className={`relative mb-3 transition-all duration-500 cubic-bezier(0.16, 1, 0.3, 1) ${
        isDeleting
          ? "opacity-0 scale-90 -translate-x-full h-0 mb-0 pointer-events-none"
          : "h-auto opacity-100 scale-100"
      }`}
    >
      {/* 背景层：删除/确认操作按钮 */}
      <div
        className={`absolute inset-y-0 right-0 flex items-center justify-center rounded-xl transition-all duration-300 ease-in-out ${
          isConfirming ? "bg-orange-500" : "bg-red-500"
        }`}
        style={{
          width: `${deleteBtnWidth}px`,
          opacity: swipeOffset < 0 ? 1 : 0,
        }}
      >
        <button
          onClick={handleDeleteClick}
          // 防止点击按钮本身触发卡片的点击事件（尽管有z-index，但为了保险）
          onMouseDown={(e) => e.stopPropagation()}
          onTouchStart={(e) => e.stopPropagation()}
          className="w-full h-full flex flex-col items-center justify-center text-white active:brightness-90 transition-all rounded-r-xl overflow-hidden"
        >
          {isConfirming ? (
            <>
              <AlertCircle className="w-6 h-6 mb-1 animate-bounce" />
              <span className="text-[10px] font-bold whitespace-nowrap px-1">
                确认删除?
              </span>
            </>
          ) : (
            <>
              <Trash2 className="w-6 h-6 mb-1" />
              <span className="text-[10px] font-bold">删除</span>
            </>
          )}
        </button>
      </div>

      {/* 主体卡片 */}
      <div
        onClick={handleCardClick}
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
        className="relative bg-white rounded-xl p-4 shadow-sm border border-slate-100 active:bg-slate-50 transition-transform duration-200 cursor-pointer z-10 select-none"
        style={{
          transform: `translateX(${swipeOffset}px)`,
          transition: isSwiping
            ? "none"
            : "transform 0.3s cubic-bezier(0.16, 1, 0.3, 1)",
        }}
      >
        <div className="flex justify-between items-center mb-2">
          <h3 className="font-semibold text-slate-800 text-lg line-clamp-1">
            {task.title}
          </h3>
          {getSyncIcon(task.syncState)}
        </div>

        <div className="flex items-center text-slate-500 text-sm mb-3 space-x-4">
          <div className="flex items-center space-x-1">
            <Calendar className="w-3.5 h-3.5" />
            <span>{new Date(task.startTime).toLocaleDateString("zh-CN")}</span>
          </div>
          <div className="flex items-center space-x-1">
            <Clock className="w-3.5 h-3.5" />
            <span>
              {new Date(task.startTime).toLocaleTimeString("zh-CN", {
                hour: "2-digit",
                minute: "2-digit",
              })}
            </span>
          </div>
        </div>

        <div className="flex items-center justify-between mt-2">
          <div
            className={`px-2.5 py-1 rounded-full text-xs font-medium flex items-center space-x-1 ${
              task.isFinished
                ? "bg-green-100 text-green-700"
                : "bg-blue-100 text-blue-700"
            }`}
          >
            {task.isFinished ? (
              <CheckCircle2 className="w-3 h-3" />
            ) : (
              <MapPin className="w-3 h-3" />
            )}
            <span>{task.isFinished ? "已完成" : "进行中"}</span>
          </div>

          <div className="text-xs text-slate-400">
            编号: {task.taskId.slice(0, 8)}...
          </div>
        </div>
      </div>
    </div>
  );
};
