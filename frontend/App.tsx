import React, { useEffect, useMemo, useState } from "react";
import { Toast, ToastType } from "./components/Toast";
// import { mockAuthService } from "./services/mockAuth";
import { authService } from "./services/authService";
import {
  InspectionRecord,
  InspectionTask,
  NativeApiResponse,
  User,
  ViewState,
} from "./types";

// Sub Components
import { CreateTaskModal } from "./components/CreateTaskModal";
import { Header } from "./components/Header";
import { ImagePreview } from "./components/ImagePreview";
import { LoginView } from "./components/LoginView";
import { TaskDetailView } from "./components/TaskDetailView";
import { TaskListView } from "./components/TaskListView";
import { UserCenterView } from "./components/UserCenterView";

const App: React.FC = () => {
  // Global State
  const [currentUser, setCurrentUser] = useState<User | null>(null);

  // Navigation State
  const [currentView, setCurrentView] = useState<ViewState>(ViewState.LOGIN);

  // Data State
  const [tasks, setTasks] = useState<InspectionTask[]>([]);
  const [selectedTask, setSelectedTask] = useState<InspectionTask | null>(null);
  const [taskRecords, setTaskRecords] = useState<InspectionRecord[]>([]);
  const [filterTerm, setFilterTerm] = useState("");
  const [previewImage, setPreviewImage] = useState<string | null>(null);
  const [toast, setToast] = useState<{
    id: number;
    visible: boolean;
    title: string;
    message: string;
    type: ToastType;
  } | null>(null);

  // Syncing State
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncResult, setSyncResult] = useState<{
    type: "success" | "error";
    message: string;
  } | null>(null);

  // Modal State
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);

  const showToast = (
    title: string,
    message: string,
    type: ToastType = "info",
  ) => {
    setToast({ id: Date.now(), visible: true, title, message, type });
  };

  // --- Initialization & Bridge Setup ---

  useEffect(() => {
    // [MOCK ONLY] Initialize Mock Bridge.
    // setupMockAndroidBridge();

    // [KEEP IN PRODUCTION] Register Global Callbacks for Native Bridge
    window.onTasksReceived = (
      response: NativeApiResponse<InspectionTask[]>,
    ) => {
      console.log("📂 [App] Native onTasksReceived:", response.msg);

      if (response.data) {
        setTasks(response.data);
      }

      // const isLocalData = response.msg.includes("本地");

      if (response.code === 200) {
        // if (!isLocalData) {
        // Server data received
        setSyncResult({ type: "success", message: "列表已更新" });
        setIsSyncing(false);
        // } else {
        //   // Local data, keep loading
        // }
      } else {
        // Error case
        console.warn("⚠️ [App] Native fetchTasks warning:", response.msg);
        setSyncResult({ type: "error", message: "同步失败" });
        setIsSyncing(false);
      }
    };

    window.onRecordsReceived = (
      response: NativeApiResponse<InspectionRecord[]>,
    ) => {
      console.log("📂 [App] Native onRecordsReceived:", response.msg);

      if (response.data) {
        setTaskRecords(response.data);
      }

      // const isLocalData = response.msg.includes("本地");

      if (response.code === 200) {
        // if (!isLocalData) {
        setSyncResult({ type: "success", message: "记录已更新" });
        setIsSyncing(false);
        // }
      } else {
        console.warn("⚠️ [App] Native fetchRecords warning:", response.msg);
        setSyncResult({ type: "error", message: "同步失败" });
        setIsSyncing(false);
      }
    };

    window.onLogoutComplete = (response: NativeApiResponse<void>) => {
      console.log("🔒 [App] Native logout complete:", response.msg);

      // 1. 清空 React 全局状态
      setCurrentUser(null);
      setTasks([]);
      setTaskRecords([]);
      setSyncResult(null);

      // 2. 路由跳转
      setCurrentView(ViewState.LOGIN);

      // 3. 提示用户
      if (response.code === 200) {
        showToast("已退出", "您已安全退出登录", "success");
      } else {
        // 这种情况理论上很少见，因为本地清理通常是强制成功的
        showToast("已退出", "离线模式强制登出", "info");
      }
    };

    window.onProfileUpdated = (response: NativeApiResponse<User>) => {
      console.log("👤 [App] Native onProfileUpdated:", response.msg);

      if (response.code === 200 && response.data) {
        // 更新本地用户状态
        setCurrentUser(response.data);
        showToast("修改成功", "个人资料已更新", "success");
      } else {
        showToast("修改失败", response.msg || "未知错误", "error");
      }
    };

    let cachedUserStr = "";
    cachedUserStr = window.AndroidNative.tryAutoLogin();

    if (cachedUserStr) {
      try {
        const user = JSON.parse(cachedUserStr) as User;
        console.log("🚀 [App] 自动登录成功:", user.username);

        // 恢复状态
        setCurrentUser(user);
        setCurrentView(ViewState.LIST);
      } catch (e) {
        console.error("自动登录数据解析失败", e);
      }
    }

    // Cleanup (optional)
    return () => {
      window.onTasksReceived = undefined;
      window.onRecordsReceived = undefined;
      window.onLogoutComplete = undefined;
      window.onProfileUpdated = undefined;
    };
  }, []);

  // --- Data Loading Triggers ---

  const requestTasks = (userId: string) => {
    if (window.AndroidNative) {
      console.log("🚀 [App] Calling AndroidNative.fetchTasks...");
      setSyncResult(null);
      setIsSyncing(true);
      window.AndroidNative.fetchTasks(userId);
    } else {
      console.warn("⚠️ [App] AndroidNative interface not found");
      showToast("环境错误", "未检测到原生接口", "error");
    }
  };

  const requestRecords = (taskId: string) => {
    if (window.AndroidNative) {
      console.log(`🚀 [App] Calling AndroidNative.fetchRecords(${taskId})...`);
      setSyncResult(null);
      setIsSyncing(true);
      window.AndroidNative.fetchRecords(taskId);
    } else {
      console.warn("⚠️ [App] AndroidNative interface not found");
    }
  };

  // Initial Data Load on Login
  useEffect(() => {
    if (currentUser) {
      requestTasks(currentUser.id);
    }
  }, [currentUser]);

  // --- Event Handlers ---

  const handleRefreshTasks = async () => {
    if (!currentUser) return;
    requestTasks(currentUser.id);
  };

  const handleRefreshRecords = async () => {
    if (!selectedTask) return;
    requestRecords(selectedTask.taskId);
  };

  const handleLogin = async (u: string, p: string) => {
    // 1. 发起网络请求
    const res = await authService.login(u, p);

    // 2. 校验响应结果
    if (res.code === 200 && res.data) {
      const authData = res.data;

      // 3. 构造纯净的用户对象
      const user: User = {
        id: authData.id,
        username: authData.username,
        role: authData.role,
      };

      // 4. 更新 React 全局状态
      setCurrentUser(user);

      // 5. 调用 Android 原生接口保存完整会话信息
      // 传入: AccessToken, RefreshToken, User对象(JSON字符串)
      if (window.AndroidNative && window.AndroidNative.saveLoginState) {
        console.log(
          "📥 [App] Login Success: Saving session to Native Layer...",
        );
        window.AndroidNative.saveLoginState(
          authData.accessToken,
          authData.refreshToken,
          JSON.stringify(user),
        );
      } else {
        // 生产环境如果缺失 Bridge 接口，属于严重异常
        console.error("❌ [App] Critical: AndroidNative interface not found!");
        showToast("环境异常", "无法与原生应用通信，请联系管理员", "error");
        // 虽然 UI 状态更新了，但无法持久化，下次启动会失效
      }

      // 6. 路由跳转与反馈
      setCurrentView(ViewState.LIST);
      showToast("登录成功", `欢迎回来, ${authData.username}`, "success");
      return true;
    } else {
      // 登录失败处理
      console.warn(`⚠️ [App] Login Failed: ${res.message}`);
      showToast("登录失败", res.message, "error");
      return false;
    }
  };

  const handleRegister = async (u: string, p: string) => {
    // 1. 发起网络请求
    const res = await authService.register(u, p);

    // 2. 校验响应结果
    if (res.code === 200 && res.data) {
      const authData = res.data;

      // 3. 构造用户对象
      const user: User = {
        id: authData.id,
        username: authData.username,
        role: authData.role,
      };

      // 4. 更新 React 全局状态
      setCurrentUser(user);

      // 5. 调用 Android 原生接口保存完整会话信息
      // 注册成功后直接进入应用，无需再次登录
      if (window.AndroidNative && window.AndroidNative.saveLoginState) {
        console.log(
          "📥 [App] Register Success: Saving session to Native Layer...",
        );
        window.AndroidNative.saveLoginState(
          authData.accessToken,
          authData.refreshToken,
          JSON.stringify(user),
        );
      } else {
        console.error("❌ [App] Critical: AndroidNative interface not found!");
        showToast("环境异常", "无法与原生应用通信", "error");
      }

      // 6. 路由跳转与反馈
      setCurrentView(ViewState.LIST);
      showToast("注册成功", `欢迎加入, ${authData.username}`, "success");
      return true;
    } else {
      // 注册失败处理
      console.warn(`⚠️ [App] Register Failed: ${res.message}`);
      showToast("注册失败", res.message, "error");
      return false;
    }
  };

  const handleLogout = () => {
    showToast("正在退出...", "正在清理安全凭证", "info");

    if (window.AndroidNative && window.AndroidNative.logout) {
      // 正式环境：移交 Native 托管
      window.AndroidNative.logout();
    } else {
      // 浏览器调试环境 (Fallback)
      // 模拟 Native 的回调行为，方便在 Chrome 里调试业务流程
      console.warn("⚠️ [App] Browser Env: Simulating Native Logout");
      localStorage.clear();

      // 模拟异步回调
      setTimeout(() => {
        if (window.onLogoutComplete) {
          window.onLogoutComplete({
            code: 200,
            msg: "Browser Local Logout",
            data: undefined,
          });
        }
      }, 500);
    }
  };

  const handleUpdateProfile = (newUsername?: string, newPassword?: string) => {
    if (!currentUser) return false;

    // 参数归一化：将 undefined 转为 null 传给 Kotlin
    const uName = newUsername || null;
    const pwd = newPassword || null;

    if (window.AndroidNative && window.AndroidNative.updateProfile) {
      window.AndroidNative.updateProfile(currentUser.id, uName, pwd);
      // 注意：这里不能立马返回 true/false，因为是异步的。
      // UI 层（UserCenterView）可能需要调整 Loading 状态的逻辑，
      // 或者我们可以简单地让 Modal 保持打开，直到收到 Toast。
      return true;
    }
  };

  // --- View Navigation ---

  const handleTaskClick = async (task: InspectionTask) => {
    setSelectedTask(task);
    setTaskRecords([]);
    setCurrentView(ViewState.DETAIL);
    setSyncResult(null); // Clear previous results to avoid stale animation
    window.scrollTo(0, 0);
    requestRecords(task.taskId);
  };

  const handleBack = () => {
    // Clear sync result when navigating back to avoid "Success" animation on mount
    setSyncResult(null);

    if (currentView === ViewState.USER_CENTER) {
      setCurrentView(ViewState.LIST);
      return;
    }

    // Returning from Detail
    setSelectedTask(null);
    setTaskRecords([]);
    setCurrentView(ViewState.LIST);
    // Removed automatic refresh request here
  };

  const handleCreateTask = (newTaskName: string) => {
    if (!currentUser) return;

    setIsCreateModalOpen(false);

    // Navigate to inspection.html and pass userId and taskName via URL params.
    const params = new URLSearchParams({
      userId: currentUser.id,
      taskName: newTaskName,
    });
    const url = `./inspection.html?${params.toString()}`;

    if (window.AndroidNative && window.AndroidNative.startInspectionActivity) {
      window.AndroidNative.startInspectionActivity(url);
    } else {
      // 兼容在浏览器调试的情况
      window.location.href = url;
    }
  };

  const handleSyncStatusClick = (status: number) => {
    let title = "";
    let message = "";
    let type: ToastType = "info";
    switch (status) {
      case 0:
        title = "本地新建 (未同步)";
        message = "此任务仅保存在本地，需要连接网络以上传服务器。";
        type = "info";
        break;
      case 1:
        title = "已同步 (进行中)";
        message = "任务已备份至服务器，目前仍在进行中。";
        type = "success";
        break;
      case 2:
        title = "已归档 (完成)";
        message = "任务已结束，所有数据已完整同步至服务器。";
        type = "success";
        break;
      default:
        title = "同步状态未知";
        message = "无法获取当前任务的同步状态。";
        type = "error";
    }
    showToast(title, message, type);
  };

  const handleMapClick = () => {
    showToast("功能开发中", "地图轨迹查看功能即将上线，敬请期待！", "info");
  };

  const filteredTasks = useMemo(() => {
    return tasks.filter(
      (t) =>
        t.title.toLowerCase().includes(filterTerm.toLowerCase()) ||
        t.taskId.includes(filterTerm),
    );
  }, [tasks, filterTerm]);

  // --- Render ---

  if (!currentUser || currentView === ViewState.LOGIN) {
    return (
      <div className="min-h-screen bg-slate-50 font-sans relative">
        {toast && toast.visible && (
          <Toast
            key={toast.id}
            title={toast.title}
            message={toast.message}
            type={toast.type}
            onClose={() => setToast(null)}
          />
        )}
        <LoginView
          onLogin={handleLogin}
          onRegister={handleRegister}
          showToast={showToast}
        />
      </div>
    );
  }

  if (currentView === ViewState.USER_CENTER) {
    return (
      <div className="min-h-screen bg-slate-50 font-sans relative">
        {toast && toast.visible && (
          <Toast
            key={toast.id}
            title={toast.title}
            message={toast.message}
            type={toast.type}
            onClose={() => setToast(null)}
          />
        )}
        <UserCenterView
          user={currentUser}
          onLogout={handleLogout}
          onUpdateProfile={handleUpdateProfile}
          onBack={handleBack}
          showToast={showToast}
        />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50 font-sans relative flex flex-col">
      {toast && toast.visible && (
        <Toast
          key={toast.id}
          title={toast.title}
          message={toast.message}
          type={toast.type}
          onClose={() => setToast(null)}
        />
      )}
      <ImagePreview src={previewImage} onClose={() => setPreviewImage(null)} />
      <CreateTaskModal
        isOpen={isCreateModalOpen}
        onClose={() => setIsCreateModalOpen(false)}
        onCreate={handleCreateTask}
      />

      <Header
        currentView={currentView}
        selectedTask={selectedTask}
        filterTerm={filterTerm}
        onFilterChange={setFilterTerm}
        onBack={handleBack}
        onShowToast={showToast}
        onUserClick={() => {
          setSyncResult(null); // Clear result when leaving current view
          setCurrentView(ViewState.USER_CENTER);
        }}
        onMapClick={handleMapClick}
        user={currentUser}
      />

      <main className="flex-1 flex flex-col">
        {currentView === ViewState.LIST ? (
          <TaskListView
            tasks={filteredTasks}
            onTaskClick={handleTaskClick}
            onSyncStatusClick={handleSyncStatusClick}
            onCreateClick={() => setIsCreateModalOpen(true)}
            onRefresh={handleRefreshTasks}
            isRefreshing={isSyncing}
            refreshResult={syncResult}
          />
        ) : (
          selectedTask && (
            <TaskDetailView
              task={selectedTask}
              records={taskRecords}
              onImageClick={setPreviewImage}
              onRefresh={handleRefreshRecords}
              isRefreshing={isSyncing}
              refreshResult={syncResult}
            />
          )
        )}
      </main>
    </div>
  );
};

export default App;
