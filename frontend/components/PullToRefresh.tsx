import { ArrowDown, CheckCircle2, Loader2, XCircle } from "lucide-react";
import React, { useEffect, useRef, useState } from "react";

export interface RefreshResult {
  type: "success" | "error";
  message: string;
}

interface PullToRefreshProps {
  onRefresh: () => Promise<void>;
  children: React.ReactNode;
  isRefreshing?: boolean;
  refreshResult?: RefreshResult | null;
}

export const PullToRefresh: React.FC<PullToRefreshProps> = ({
  onRefresh,
  children,
  isRefreshing: externalRefreshing,
  refreshResult,
}) => {
  const [startY, setStartY] = useState(0);
  const [currentY, setCurrentY] = useState(0);
  const [internalRefreshing, setInternalRefreshing] = useState(false);
  const [isDragging, setIsDragging] = useState(false);
  const [showingResult, setShowingResult] = useState(false);
  const contentRef = useRef<HTMLDivElement>(null);

  const PULL_THRESHOLD = 80;
  const MAX_DRAG = 120;
  const LOADING_HEIGHT = 60;

  // Sync internal state with external prop and trigger animation
  useEffect(() => {
    if (externalRefreshing !== undefined) {
      setInternalRefreshing(externalRefreshing);
      if (externalRefreshing) {
        // Programmatically expand
        setCurrentY(LOADING_HEIGHT);
        setShowingResult(false);
      } else {
        // Finished Loading
        if (refreshResult) {
          setShowingResult(true);
          setCurrentY(LOADING_HEIGHT);

          // Hold result for 1.5s then collapse
          const timer = setTimeout(() => {
            setCurrentY(0);
            // Reset showing result after animation finishes
            setTimeout(() => setShowingResult(false), 300);
          }, 1500);
          return () => clearTimeout(timer);
        } else {
          // No result, collapse immediately
          setCurrentY(0);
          setShowingResult(false);
        }
      }
    }
  }, [externalRefreshing, refreshResult]);

  const isRefreshing =
    externalRefreshing !== undefined ? externalRefreshing : internalRefreshing;

  // --- Core Logic ---

  const handleStart = (clientY: number) => {
    if (window.scrollY > 0 || isRefreshing || showingResult) return;
    setStartY(clientY);
    setIsDragging(true);
  };

  const handleMove = (clientY: number) => {
    if (!isDragging || window.scrollY > 0 || isRefreshing || showingResult)
      return;

    const diff = clientY - startY;

    if (diff > 0) {
      const dampedDiff = Math.min(diff * 0.5, MAX_DRAG);
      setCurrentY(dampedDiff);
    }
  };

  const handleEnd = async () => {
    if (!isDragging) return;
    setIsDragging(false);

    if (window.scrollY > 0 || isRefreshing || showingResult) {
      if (externalRefreshing === undefined) setCurrentY(0);
      return;
    }

    if (currentY > PULL_THRESHOLD) {
      // Internal Trigger
      if (externalRefreshing === undefined) {
        setInternalRefreshing(true);
        setCurrentY(LOADING_HEIGHT);
        try {
          await onRefresh();
        } finally {
          setTimeout(() => {
            setInternalRefreshing(false);
            setCurrentY(0);
          }, 500);
        }
      } else {
        // External Trigger
        setCurrentY(LOADING_HEIGHT);
        await onRefresh();
      }
    } else {
      setCurrentY(0);
    }
  };

  // --- Touch Events ---
  const onTouchStart = (e: React.TouchEvent) =>
    handleStart(e.touches[0].clientY);
  const onTouchMove = (e: React.TouchEvent) => handleMove(e.touches[0].clientY);
  const onTouchEnd = () => handleEnd();

  // --- Mouse Events ---
  const onMouseDown = (e: React.MouseEvent) => handleStart(e.clientY);
  const onMouseMove = (e: React.MouseEvent) => handleMove(e.clientY);
  const onMouseUp = () => handleEnd();
  const onMouseLeave = () => handleEnd();

  return (
    <div
      ref={contentRef}
      className="min-h-full relative select-none touch-pan-y"
      onTouchStart={onTouchStart}
      onTouchMove={onTouchMove}
      onTouchEnd={onTouchEnd}
      onMouseDown={onMouseDown}
      onMouseMove={onMouseMove}
      onMouseUp={onMouseUp}
      onMouseLeave={onMouseLeave}
    >
      {/* Loading/Result Indicator */}
      <div
        className="absolute top-0 left-0 right-0 flex justify-center items-center pointer-events-none"
        style={{
          height: `${Math.max(currentY, 0)}px`,
          transition: isDragging
            ? "height 0s"
            : "height 0.3s cubic-bezier(0.25, 0.1, 0.25, 1)",
          overflow: "hidden",
        }}
      >
        <div className="flex items-center space-x-2 text-slate-500 pb-2">
          {isRefreshing ? (
            <>
              <Loader2 className="w-5 h-5 animate-spin text-blue-600" />
              <span className="text-xs font-medium">正在同步云端数据...</span>
            </>
          ) : showingResult && refreshResult ? (
            <>
              {refreshResult.type === "success" ? (
                <CheckCircle2 className="w-5 h-5 text-green-500" />
              ) : (
                <XCircle className="w-5 h-5 text-red-500" />
              )}
              <span
                className={`text-xs font-medium ${refreshResult.type === "success" ? "text-green-600" : "text-red-600"}`}
              >
                {refreshResult.message}
              </span>
            </>
          ) : (
            <>
              <ArrowDown
                className="w-5 h-5 transition-transform duration-200"
                style={{
                  transform: `rotate(${currentY > PULL_THRESHOLD ? 180 : 0}deg)`,
                }}
              />
              <span className="text-xs font-medium">
                {currentY > PULL_THRESHOLD ? "释放刷新" : "下拉刷新"}
              </span>
            </>
          )}
        </div>
      </div>

      {/* Content */}
      <div
        style={{
          transform: currentY > 0 ? `translateY(${currentY}px)` : "none",
          transition: isDragging
            ? "transform 0s"
            : "transform 0.3s cubic-bezier(0.25, 0.1, 0.25, 1)",
          cursor: isDragging ? "grabbing" : "auto",
        }}
      >
        {children}
      </div>
    </div>
  );
};
