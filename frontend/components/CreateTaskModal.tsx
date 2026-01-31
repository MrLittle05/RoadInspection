import React, { useState, useEffect } from 'react';
import { X } from 'lucide-react';

interface CreateTaskModalProps {
  isOpen: boolean;
  onClose: () => void;
  onCreate: (name: string) => void;
}

export const CreateTaskModal: React.FC<CreateTaskModalProps> = ({ isOpen, onClose, onCreate }) => {
  const [taskName, setTaskName] = useState('');

  // Reset input when modal opens
  useEffect(() => {
    if (isOpen) {
      setTaskName('');
    }
  }, [isOpen]);

  const handleCreate = () => {
    if (taskName.trim()) {
      onCreate(taskName);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center px-6">
      {/* Backdrop: Blurred and darkened */}
      <div 
          className="absolute inset-0 bg-slate-900/30 backdrop-blur-sm transition-opacity"
          onClick={onClose}
      ></div>

      {/* Modal Window */}
      <div className="relative bg-white w-full max-w-sm rounded-2xl shadow-2xl p-6 z-10 animate-in fade-in zoom-in-95 duration-200">
          <h3 className="text-lg font-bold text-center text-slate-800 mb-6">新巡检任务</h3>
          
          <div className="space-y-4">
              <div>
                  <input 
                      type="text" 
                      value={taskName}
                      onChange={(e) => setTaskName(e.target.value)}
                      placeholder="请输入道路或区域名称"
                      className="w-full bg-slate-50 border border-slate-200 text-slate-800 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:bg-white transition-all"
                      autoFocus
                  />
              </div>
              
              <button 
                  onClick={handleCreate}
                  disabled={!taskName.trim()}
                  className="w-full bg-blue-600 text-white font-semibold py-3 rounded-xl shadow-lg shadow-blue-500/30 active:scale-[0.98] transition-all disabled:opacity-50 disabled:shadow-none"
              >
                  开始巡检
              </button>
          </div>

          <button 
              onClick={onClose}
              className="absolute top-4 right-4 text-slate-400 hover:text-slate-600"
          >
              <X className="w-5 h-5" />
          </button>
      </div>
    </div>
  );
};