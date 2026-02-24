import {
  Activity,
  AlertTriangle,
  Clock,
  LocateFixed,
  MapPin,
} from "lucide-react";
import React from "react";
import { InspectionRecord } from "../types";

interface RecordItemProps {
  record: InspectionRecord;
  onImageClick: (url: string) => void;
}

export const RecordItem: React.FC<RecordItemProps> = ({
  record,
  onImageClick,
}) => {
  // Determine color based on IRI (Roughness)
  const getIriColor = (iri: number) => {
    if (iri < 2.0) return "text-emerald-700 bg-emerald-50 border-emerald-100";
    if (iri < 4.0) return "text-amber-700 bg-amber-50 border-amber-100";
    return "text-red-700 bg-red-50 border-red-100";
  };

  const hasDistress =
    record.pavementDistress && record.pavementDistress.length > 0;

  const imageSrc = React.useMemo(() => {
    // 优先使用本地路径
    if (record.localPath && record.localPath.length > 0) {
      // 如果是绝对路径且没有协议头，添加 file:// 前缀
      if (
        record.localPath.startsWith("/") &&
        !record.localPath.startsWith("file://")
      ) {
        return `file://${record.localPath}`;
      }
      return record.localPath;
    }
    // 其次使用服务端 URL
    if (record.serverUrl && record.serverUrl.length > 0) {
      return record.serverUrl;
    }
    return null;
  }, [record.localPath, record.serverUrl]);

  return (
    <div className="flex bg-white p-3 rounded-xl border border-slate-100 shadow-sm mb-3 transition-transform active:scale-[0.99]">
      {/* Thumbnail */}
      <div
        className="relative w-24 h-24 flex-shrink-0 bg-slate-100 rounded-lg overflow-hidden cursor-zoom-in"
        onClick={(e) => {
          e.stopPropagation();
          if (imageSrc) onImageClick(imageSrc);
        }}
      >
        {imageSrc ? (
          <img
            src={imageSrc}
            alt="现场照片"
            className="w-full h-full object-cover"
            loading="lazy"
            decoding="async"
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center text-slate-400 text-xs">
            无图
          </div>
        )}
        {/* Sync Status Dot */}
        {record.syncStatus === 1 && (
          <div className="absolute top-1.5 right-1.5 w-2.5 h-2.5 bg-amber-500 rounded-full border-2 border-white shadow-sm ring-1 ring-black/5"></div>
        )}
      </div>

      {/* Content */}
      <div className="ml-3 flex-1 flex flex-col justify-between py-0.5 min-w-0">
        {/* Info Rows Container - Fixed width icon containers ensure vertical alignment */}
        <div className="space-y-1">
          {/* Address Row */}
          <div className="flex items-center">
            <div className="w-5 flex items-center justify-center flex-shrink-0 mr-1.5">
              <MapPin className="w-4 h-4 text-slate-800" strokeWidth={2.5} />
            </div>
            <span className="text-base font-bold text-slate-800 truncate">
              {record.address || "未知位置"}
            </span>
          </div>

          {/* Coordinates Row */}
          <div className="flex items-center text-slate-400">
            <div className="w-5 flex items-center justify-center flex-shrink-0 mr-1.5">
              <LocateFixed className="w-3.5 h-3.5" />
            </div>
            <span className="text-xs font-mono font-medium tracking-tight">
              {record.latitude.toFixed(6)}, {record.longitude.toFixed(6)}
            </span>
          </div>

          {/* Time Row */}
          <div className="flex items-center text-slate-400">
            <div className="w-5 flex items-center justify-center flex-shrink-0 mr-1.5">
              <Clock className="w-3.5 h-3.5" />
            </div>
            <span className="text-xs font-medium">
              {new Date(record.captureTime).toLocaleDateString()}{" "}
              {new Date(record.captureTime).toLocaleTimeString("zh-CN", {
                hour: "2-digit",
                minute: "2-digit",
              })}
            </span>
          </div>
        </div>

        {/* Badges Row */}
        <div className="flex items-center gap-2 mt-2">
          {/* IRI Badge */}
          <div
            className={`px-2 py-0.5 rounded border text-xs font-bold flex items-center shadow-sm ${getIriColor(record.iri)}`}
          >
            <Activity className="w-3 h-3 mr-1" />
            IRI {record.iri !== null ? record.iri.toFixed(2) : "N/A"}
          </div>

          {/* Distress Badges */}
          {hasDistress ? (
            record.pavementDistress.slice(0, 2).map((distress, idx) => (
              <div
                key={idx}
                className="flex items-center px-2 py-0.5 rounded bg-red-50 border border-red-100 text-red-600 text-xs font-bold shadow-sm"
              >
                <AlertTriangle className="w-3 h-3 mr-1" />
                {distress}
              </div>
            ))
          ) : (
            <span className="text-xs text-slate-400 font-medium px-1">
              无病害
            </span>
          )}

          {hasDistress && record.pavementDistress.length > 2 && (
            <span className="text-xs text-slate-400">
              +{record.pavementDistress.length - 2}
            </span>
          )}
        </div>
      </div>
    </div>
  );
};
