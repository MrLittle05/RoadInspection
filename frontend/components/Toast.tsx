import React, { useEffect, useState } from 'react';
import { X, Info, CheckCircle, AlertCircle } from 'lucide-react';

export type ToastType = 'info' | 'success' | 'error';

interface ToastProps {
  message: string;
  title?: string;
  type?: ToastType;
  onClose: () => void;
}

export const Toast: React.FC<ToastProps> = ({ message, title, type = 'info', onClose }) => {
  // Start invisible to allow entrance animation
  const [isVisible, setIsVisible] = useState(false);

  useEffect(() => {
    // Trigger enter animation slightly after mount to ensure transition plays
    const enterTimer = setTimeout(() => {
      setIsVisible(true);
    }, 50);

    const closeTimer = setTimeout(() => {
      handleClose();
    }, 3000); // Auto close after 3 seconds

    return () => {
      clearTimeout(enterTimer);
      clearTimeout(closeTimer);
    };
  }, []);

  const handleClose = () => {
    setIsVisible(false);
    // Wait for animation to finish before unmounting in parent
    setTimeout(onClose, 300);
  };

  const getIcon = () => {
    switch (type) {
      case 'success': return <CheckCircle className="w-5 h-5 text-green-500" />;
      case 'error': return <AlertCircle className="w-5 h-5 text-red-500" />;
      default: return <Info className="w-5 h-5 text-blue-500" />;
    }
  };

  return (
    <div 
      className={`fixed top-6 left-4 right-4 z-[110] flex items-start p-4 bg-white rounded-xl shadow-2xl border border-slate-100 transition-all duration-500 cubic-bezier(0.16, 1, 0.3, 1) ${
        isVisible ? 'opacity-100 translate-y-0 scale-100' : 'opacity-0 -translate-y-8 scale-95'
      }`}
    >
      <div className="flex-shrink-0 mt-0.5 mr-3">
        {getIcon()}
      </div>
      <div className="flex-1 mr-2">
        {title && <h4 className="font-semibold text-slate-800 text-sm mb-1">{title}</h4>}
        <p className="text-sm text-slate-600 leading-relaxed">{message}</p>
      </div>
      <button 
        onClick={handleClose} 
        className="text-slate-400 hover:text-slate-600 transition-colors p-1 -mr-2 -mt-2"
      >
        <X className="w-4 h-4" />
      </button>
    </div>
  );
};