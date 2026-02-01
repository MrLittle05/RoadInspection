// Matching the provided Kotlin Entity definitions

export interface InspectionTask {
  taskId: string;
  title: string;
  startTime: number;
  endTime: number | null;
  inspectorId: string;
  isFinished: boolean;
  syncState: number; // 0 = Local Only, 1 = Synced (Active), 2 = Finalized
}

export interface InspectionRecord {
  id: number;
  taskId: string;
  localPath: string;
  serverUrl: string | null;
  syncStatus: number; // 0 = Synced, 1 = Pending
  captureTime: number;
  latitude: number;
  longitude: number;
  address: string | null;
  iri: number; // International Roughness Index
  pavementDistress: string[]; // Changed to array to support multiple distresses
}

export interface User {
  id: string;
  username: string;
  role: "admin" | "inspector";
}

// Native Response Wrapper
export interface NativeApiResponse<T> {
  code: number;
  msg: string;
  data: T;
}

// Global Window Interface for Android Bridge
declare global {
  interface Window {
    // 1. Android 注入的方法 (Frontend -> Native)
    AndroidNative: {
      /**
       * 触发获取任务列表
       * @param userId 当前用户ID，原生端根据此ID获取任务
       * 原生端处理完毕后会调用 window.onTasksReceived
       */
      fetchTasks(userId: string): void;

      /**
       * 触发获取指定任务的记录
       * 原生端处理完毕后会调用 window.onRecordsReceived
       */
      fetchRecords(taskId: string): void;

      /**
       * 触发原生端加载巡检页面
       * @param url 巡检页面路径及路径参数
       */
      startInspectionActivity(url: string): void;

      getApiBaseUrl(): string;
    };

    // 2. 前端挂载的回调 (Native -> Frontend)
    /**
     * 原生端回传任务列表数据
     */
    onTasksReceived?: (response: NativeApiResponse<InspectionTask[]>) => void;

    /**
     * 原生端回传巡检记录数据
     */
    onRecordsReceived?: (
      response: NativeApiResponse<InspectionRecord[]>,
    ) => void;
  }
}

// UI specific types
export enum ViewState {
  LOGIN = "LOGIN",
  LIST = "LIST",
  DETAIL = "DETAIL",
  USER_CENTER = "USER_CENTER",
}
