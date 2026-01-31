import React from 'react';
import { InspectionTask } from '../types';
import { Calendar, MapPin, CheckCircle2, Clock, Cloud, CloudOff, CloudUpload } from 'lucide-react';

interface TaskCardProps {
  task: InspectionTask;
  onTaskClick: (task: InspectionTask) => void;
  onSyncStatusClick: (status: number) => void;
}

export const TaskCard: React.FC<TaskCardProps> = ({ task, onTaskClick, onSyncStatusClick }) => {
  const handleSyncClick = (e: React.MouseEvent, state: number) => {
    e.stopPropagation();
    onSyncStatusClick(state);
  };

  const getSyncIcon = (state: number) => {
    // Increased icon size for better visibility (w-7 h-7 = 28px)
    const iconSize = "w-7 h-7"; 
    
    // Adjusted padding and negative margin for the larger icon
    const btnClass = "p-2 -mr-2 rounded-full transition-colors active:scale-95";

    switch (state) {
      case 0: // Local Only
        return (
          <button 
            onClick={(e) => handleSyncClick(e, state)} 
            className={`${btnClass} hover:bg-slate-100 text-slate-400`} 
            aria-label="查看状态: 本地"
            title="本地新建 (未同步)"
          >
            <CloudUpload className={iconSize} strokeWidth={1.5} />
          </button>
        );
      case 1: // Synced (Active)
        return (
           <button 
             onClick={(e) => handleSyncClick(e, state)} 
             className={`${btnClass} hover:bg-blue-50 text-blue-500`} 
             aria-label="查看状态: 已同步"
             title="已同步 (进行中)"
           >
            <Cloud className={iconSize} strokeWidth={1.5} />
          </button>
        );
      case 2: // Finalized
        return (
           <button 
             onClick={(e) => handleSyncClick(e, state)} 
             className={`${btnClass} hover:bg-green-50 text-green-500`} 
             aria-label="查看状态: 已归档"
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
             aria-label="查看状态: 错误"
             title="同步状态未知"
           >
            <CloudOff className={iconSize} strokeWidth={1.5} />
          </button>
        );
    }
  };

  return (
    <div 
      onClick={() => onTaskClick(task)}
      className="bg-white rounded-xl p-4 shadow-sm border border-slate-100 active:scale-[0.98] transition-transform duration-200 cursor-pointer mb-3"
    >
      <div className="flex justify-between items-center mb-2">
        <h3 className="font-semibold text-slate-800 text-lg line-clamp-1">{task.title}</h3>
        {getSyncIcon(task.syncState)}
      </div>

      <div className="flex items-center text-slate-500 text-sm mb-3 space-x-4">
        <div className="flex items-center space-x-1">
          <Calendar className="w-3.5 h-3.5" />
          <span>{new Date(task.startTime).toLocaleDateString('zh-CN')}</span>
        </div>
        <div className="flex items-center space-x-1">
          <Clock className="w-3.5 h-3.5" />
          <span>{new Date(task.startTime).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}</span>
        </div>
      </div>

      <div className="flex items-center justify-between mt-2">
        <div className={`px-2.5 py-1 rounded-full text-xs font-medium flex items-center space-x-1 ${
          task.isFinished 
            ? 'bg-green-100 text-green-700' 
            : 'bg-blue-100 text-blue-700'
        }`}>
          {task.isFinished ? <CheckCircle2 className="w-3 h-3" /> : <MapPin className="w-3 h-3" />}
          <span>{task.isFinished ? '已完成' : '进行中'}</span>
        </div>
        
        <div className="text-xs text-slate-400">
           编号: {task.taskId.slice(0, 8)}...
        </div>
      </div>
    </div>
  );
};