import React from 'react';
import { X } from 'lucide-react';

interface ImagePreviewProps {
  src: string | null;
  onClose: () => void;
}

export const ImagePreview: React.FC<ImagePreviewProps> = ({ src, onClose }) => {
  if (!src) return null;

  return (
    <div 
      className="fixed inset-0 z-[100] bg-black/90 backdrop-blur-sm flex items-center justify-center p-4 animate-in fade-in duration-200"
      onClick={onClose}
    >
      <button 
        className="absolute top-4 right-4 p-2 bg-white/10 text-white rounded-full hover:bg-white/20 transition-colors"
        onClick={onClose}
      >
        <X className="w-8 h-8" />
      </button>
      <img 
        src={src} 
        alt="Preview" 
        className="max-w-full max-h-full object-contain rounded-lg shadow-2xl"
        onClick={(e) => e.stopPropagation()} 
      />
    </div>
  );
};