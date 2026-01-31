import React, { useState } from 'react';
import { ViewState, InspectionTask, User } from '../types';
import { 
  ChevronLeft, 
  Search, 
  Map as MapIcon, 
  UserCircle,
  Mic,
  MicOff
} from 'lucide-react';
import { ToastType } from './Toast';

interface HeaderProps {
  currentView: ViewState;
  selectedTask: InspectionTask | null;
  filterTerm: string;
  onFilterChange: (term: string) => void;
  onBack: () => void;
  onShowToast: (title: string, message: string, type: ToastType) => void;
  onUserClick: () => void;
  onMapClick?: () => void;
  user: User | null;
}

export const Header: React.FC<HeaderProps> = ({ 
  currentView, 
  selectedTask, 
  filterTerm, 
  onFilterChange, 
  onBack,
  onShowToast,
  onUserClick,
  onMapClick,
  user
}) => {
  const [isListening, setIsListening] = useState(false);

  const handleVoiceSearch = () => {
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      onShowToast("不支持语音", "您的浏览器不支持语音搜索功能", 'error');
      return;
    }

    if (isListening) {
      setIsListening(false); 
      return;
    }

    const recognition = new SpeechRecognition();
    recognition.lang = 'zh-CN';
    recognition.continuous = false;
    recognition.interimResults = false;

    recognition.onstart = () => {
      setIsListening(true);
    };

    recognition.onresult = (event: any) => {
      const transcript = event.results[0][0].transcript;
      onFilterChange(transcript);
      setIsListening(false);
    };

    recognition.onerror = (event: any) => {
      console.error('Voice search error:', event.error);
      setIsListening(false);
      onShowToast("语音识别错误", "无法识别您的语音，请重试。", 'error');
    };

    recognition.onend = () => {
      setIsListening(false);
    };

    recognition.start();
  };

  if (currentView === ViewState.DETAIL && selectedTask) {
    return (
      <header className="sticky top-0 z-50 bg-white/90 backdrop-blur-md border-b border-slate-200 px-4 py-3 flex items-center justify-between">
        <button 
          onClick={onBack}
          className="p-2 -ml-2 text-slate-600 hover:bg-slate-100 rounded-full transition-colors"
        >
          <ChevronLeft className="w-6 h-6" />
        </button>
        <div className="flex-1 text-center">
          <h1 className="font-semibold text-slate-800 text-sm line-clamp-1">{selectedTask.title}</h1>
          <p className="text-[10px] text-slate-500 uppercase tracking-wide">
              {selectedTask.isFinished ? '报告视图' : '实时巡检'}
          </p>
        </div>
        <button 
          onClick={onMapClick}
          className="p-2 -mr-2 text-slate-600 hover:bg-slate-100 rounded-full transition-colors"
          title="查看地图"
        >
          <MapIcon className="w-5 h-5" />
        </button>
      </header>
    );
  }

  return (
    <header className="sticky top-0 z-50 bg-white/90 backdrop-blur-md border-b border-slate-200 px-4 py-4">
      <div className="flex items-center justify-between mb-4">
          <h1 className="text-xl font-bold text-slate-800">
            {user ? `您好, ${user.username}!` : '我的巡检'}
          </h1>
          <button 
            onClick={onUserClick}
            className="w-9 h-9 bg-blue-100 rounded-full flex items-center justify-center text-blue-600 hover:bg-blue-200 transition-colors shadow-sm ring-2 ring-white active:scale-95"
            title={user?.username}
          >
              {user ? (
                  <span className="font-bold text-sm select-none">
                      {user.username.charAt(0).toUpperCase()}
                  </span>
              ) : (
                  <UserCircle className="w-5 h-5" />
              )}
          </button>
      </div>
      
      <div className="relative">
          <Search className="absolute left-3 top-2.5 w-4 h-4 text-slate-400" />
          <input 
              type="text" 
              placeholder={isListening ? "正在聆听..." : "搜索任务..."}
              value={filterTerm}
              onChange={(e) => onFilterChange(e.target.value)}
              className={`w-full bg-slate-100 text-slate-800 rounded-lg pl-9 pr-10 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 transition-all ${isListening ? 'ring-2 ring-blue-500 bg-blue-50' : ''}`}
          />
          <button 
              onClick={handleVoiceSearch}
              className={`absolute right-2 top-1.5 p-1 rounded-full hover:bg-slate-200 transition-colors ${isListening ? 'text-red-500 animate-pulse' : 'text-slate-400'}`}
              title="语音搜索"
          >
              {isListening ? <MicOff className="w-4 h-4" /> : <Mic className="w-4 h-4" />}
          </button>
      </div>
    </header>
  );
};