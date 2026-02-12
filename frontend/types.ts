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
  syncStatus: number;
  captureTime: number;
  latitude: number;
  longitude: number;
  address: string | null;
  iri: number | null; // International Roughness Index
  pavementDistress: string[] | null; // Changed to array to support multiple distresses
}

export interface User {
  id: string;
  username: string;
  role: "admin" | "inspector";
}

export interface AuthData extends User {
  accessToken: string;
  refreshToken: string;
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
       * 触发更新个人资料
       */
      updateProfile(
        userId: string,
        newUsername: string | null,
        newPassword: string | null,
      ): void;

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
       * @param url 页面路径 (e.g. "inspection.html")
       * @param resumeTaskId (可选) 如果是恢复任务，传入任务ID
       */
      startInspectionActivity(url: string, resumeTaskId?: string): void;

      getApiBaseUrl(): string;

      /**
       * 保存登录态 (Token + User)
       */
      saveLoginState(
        accessToken: string,
        refreshToken: string,
        userJson: string,
      ): void;

      /**
       * 尝试自动登录
       * @returns JSON 字符串 (User 对象) 或 空字符串
       */
      tryAutoLogin(): string;

      /**
       * 触发删除指定任务及其关联数据。
       * * 该操作是异步的：
       * 1. 原生层会立即将任务标记为“待删除”状态 (SyncState = -1)，UI 应立即移除该项。
       * 2. 原生层会启动后台 Worker 与服务器同步删除操作。
       * 3. 最终在本地数据库执行物理删除。
       * * @param taskId 要删除的任务的 ID
       */
      deleteTask(taskId: string): void;

      /**
       * 触发原生端接管的退登流程。
       * 原生端处理完毕（网络请求+本地清理）后，会调用 window.onLogoutComplete
       */
      logout(): void;
    };

    /**
     * 原生端资料更新完成后的回调
     */
    onProfileUpdated?: (response: NativeApiResponse<User>) => void;

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

    /**
     * 原生端用户退登完成后的回调
     * @param response 包含注销结果（即使网络失败，code 通常也是 200，因为本地注销是强制成功的）
     */
    onLogoutComplete?: (response: NativeApiResponse<void>) => void;
  }
}

// UI specific types
export enum ViewState {
  LOGIN = "LOGIN",
  LIST = "LIST",
  DETAIL = "DETAIL",
  USER_CENTER = "USER_CENTER",
}
