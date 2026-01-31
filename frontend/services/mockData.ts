import { InspectionTask, InspectionRecord, NativeApiResponse } from '../types';

// --- Internal Mock Data Generation Logic ---

// Mutable DB for simulation
let MOCK_TASKS_DB: InspectionTask[] = [
  {
    taskId: 'task-001',
    title: '101国道 - A路段',
    startTime: Date.now() - 86400000 * 2, // 2 days ago
    endTime: Date.now() - 86400000 * 2 + 3600000,
    inspectorId: 'user_default',
    isFinished: true,
    syncState: 2, // Finalized (Synced & Ended)
  },
  {
    taskId: 'task-002',
    title: '市区建设大道 - 2号车道',
    startTime: Date.now() - 3600000, // 1 hour ago
    endTime: null,
    inspectorId: 'user_default',
    isFinished: false,
    syncState: 1, // Synced (In Progress)
  },
  {
    taskId: 'task-003',
    title: '乡村道路 45号线',
    startTime: Date.now() - 86400000 * 5,
    endTime: Date.now() - 86400000 * 5 + 7200000,
    inspectorId: 'user_default',
    isFinished: true,
    syncState: 0, // Local Only (e.g., done offline)
  },
];

const DISTRESS_TYPES = ['坑槽', '龟裂', '车辙', '纵向裂缝', '沉陷', '松散', '泛油'];

// Store records in memory to persist them during session
let MOCK_RECORDS_DB: Record<string, InspectionRecord[]> = {};

// Helper to generate records
const generateRecordsForTaskInternal = (taskId: string): InspectionRecord[] => {
  if (MOCK_RECORDS_DB[taskId]) {
      return MOCK_RECORDS_DB[taskId];
  }

  const records: InspectionRecord[] = [];
  const count = 16; // Even number for easier splitting
  const baseLat = 34.0522;
  const baseLng = -118.2437;

  for (let i = 0; i < count; i++) {
    const isDistressed = Math.random() > 0.5;
    let currentDistresses: string[] = [];
    
    if (isDistressed) {
        const numDistresses = Math.floor(Math.random() * 2) + 1;
        const shuffled = [...DISTRESS_TYPES].sort(() => 0.5 - Math.random());
        currentDistresses = shuffled.slice(0, numDistresses);
    }

    const iri = isDistressed ? 2.5 + Math.random() * 4 : 0.5 + Math.random() * 1.5;
    
    records.push({
      id: Date.now() - (count - i) * 60000, // Unique ID based on time
      taskId: taskId,
      localPath: `mock_path_${i}`,
      serverUrl: `https://picsum.photos/400/300?random=${taskId}_${i}`, 
      syncStatus: Math.random() > 0.8 ? 1 : 0,
      captureTime: Date.now() - (count - i) * 60000,
      latitude: baseLat + (i * 0.001),
      longitude: baseLng + (i * 0.0005),
      address: `K${10 + i} + 200`,
      iri: parseFloat(iri.toFixed(2)),
      pavementDistress: currentDistresses,
    });
  }
  
  MOCK_RECORDS_DB[taskId] = records;
  return records;
};

// --- Helper to Simulate "Live" Data Updates ---
const simulateServerUpdate = (userId: string, taskId?: string) => {
    // 30% chance to add a new task when refreshing list
    if (!taskId && Math.random() > 0.7) {
        const newTaskId = `task-${Date.now()}`;
        const newTask: InspectionTask = {
            taskId: newTaskId,
            title: `新增紧急巡检 - ${new Date().getHours()}点${new Date().getMinutes()}分`,
            startTime: Date.now(),
            endTime: null,
            inspectorId: userId,
            isFinished: false,
            syncState: 1
        };
        MOCK_TASKS_DB.unshift(newTask); // Add to top
        console.log("⚡ [MockServer] Simulated new Task arrival:", newTask.title);
    }

    // 30% chance to add a new record when refreshing detail
    if (taskId && Math.random() > 0.7) {
        const records = generateRecordsForTaskInternal(taskId);
        const newRecord: InspectionRecord = {
            id: Date.now(),
            taskId: taskId,
            localPath: 'mock_new_path',
            serverUrl: `https://picsum.photos/400/300?random=${Date.now()}`,
            syncStatus: 0,
            captureTime: Date.now(),
            latitude: 34.0522,
            longitude: -118.2437,
            address: '新增监测点',
            iri: 3.5,
            pavementDistress: ['新增龟裂']
        };
        records.push(newRecord); // Add to end (chronological) or Handle sort in UI
        MOCK_RECORDS_DB[taskId] = records;
        console.log("⚡ [MockServer] Simulated new Record arrival for task:", taskId);
    }
};

const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

// --- API Simulation Logic (Now Internal to Mock Data Service) ---
const mockInternalApiService = {
    getTaskList: async (userId: string): Promise<NativeApiResponse<InspectionTask[]>> => {
        console.log(`🌐 [MockNative] Simulating Android HTTP Request: GET /api/task/list?userId=${userId}`);
        await delay(1500); // 增加延迟到1.5秒，让你看清楚加载过程

        simulateServerUpdate(userId);

        const tasks = MOCK_TASKS_DB
            .filter(t => t.inspectorId === 'user_default' || t.inspectorId === userId)
            .sort((a, b) => b.startTime - a.startTime);

        return { code: 200, msg: "success", data: tasks };
    },

    getRecordList: async (taskId: string): Promise<NativeApiResponse<InspectionRecord[]>> => {
        console.log(`🌐 [MockNative] Simulating Android HTTP Request: GET /api/record/list?taskId=${taskId}`);
        await delay(1500); // 增加延迟

        simulateServerUpdate('user_default', taskId);

        let records = generateRecordsForTaskInternal(taskId);
        records = records.sort((a, b) => a.captureTime - b.captureTime);

        return { code: 200, msg: "success", data: records };
    }
};

// ==================================================================================
// [MOCK START] - 生产环境对接时，删除以下 setupMockAndroidBridge 函数及其调用
// ==================================================================================

/**
 * 模拟 Android 原生端的行为
 * 策略：
 * 1. 立即返回“旧”的本地缓存数据 (Local Cache)
 * 2. 模拟网络请求
 * 3. 延迟后返回“新”的服务器数据 (Server Data)
 */
export const setupMockAndroidBridge = () => {
  if (!window.AndroidNative) {
    console.log('🔧 [Dev Mode] Initializing Mock Android Bridge (Async Callback Pattern)...');
    
    window.AndroidNative = {
      // 模拟前端触发 fetchTasks(userId)
      fetchTasks: async (userId: string) => {
        console.log(`📱 [MockAndroid] Received fetchTasks("${userId}") command.`);
        
        // --- 1. 模拟本地缓存 (Stale Data) ---
        // 为了演示效果，本地缓存【故意去掉】最新的那条任务
        const allTasks = MOCK_TASKS_DB
            .filter(t => t.inspectorId === 'user_default' || t.inspectorId === userId)
            .sort((a, b) => b.startTime - a.startTime);
        
        // 假设本地缓存滞后，少了一条数据
        const localTasks = allTasks.slice(1); 

        console.log("📱 [MockAndroid] Returning Local Cache immediately (Simulating stale data)...");
        if (window.onTasksReceived) {
            window.onTasksReceived({
                code: 200, 
                msg: "已加载本地缓存 (共" + localTasks.length + "条)", 
                data: localTasks
            });
        }

        // --- 2. 模拟网络请求 (Fresh Data) ---
        console.log("📱 [MockAndroid] Native is now fetching data from server...");
        try {
            // 网络请求返回完整数据
            const response = await mockInternalApiService.getTaskList(userId);
            
            console.log("📱 [MockAndroid] Server Data fetched. Calling window.onTasksReceived...");
            if (window.onTasksReceived) {
                window.onTasksReceived({
                   code: 200,
                   msg: "服务器数据同步成功 (新增" + (response.data.length - localTasks.length) + "条)",
                   data: response.data
                });
            }
        } catch (e) {
            console.error("Native Fetch Error", e);
            if (window.onTasksReceived) {
                window.onTasksReceived({ 
                    code: 500, 
                    msg: "网络请求失败，保持本地数据", 
                    data: localTasks 
                });
            }
        }
      },

      // 模拟前端触发 fetchRecords(taskId)
      fetchRecords: async (taskId: string) => {
        console.log(`📱 [MockAndroid] Received fetchRecords("${taskId}") command.`);
        
        // --- 1. 模拟本地缓存 (Incomplete Data) ---
        // 确保 DB 已初始化
        let allRecords = generateRecordsForTaskInternal(taskId); 
        allRecords = allRecords.sort((a, b) => a.captureTime - b.captureTime);

        // 为了演示效果，本地缓存只返回【前一半】的记录
        const localRecords = allRecords.slice(0, Math.floor(allRecords.length / 2));

        console.log("📱 [MockAndroid] Returning Local Cache immediately...");
        if (window.onRecordsReceived) {
             window.onRecordsReceived({
                code: 200,
                msg: "加载本地缓存记录 (共" + localRecords.length + "条)",
                data: localRecords
            });
        }

        // --- 2. 模拟网络请求 (Complete Data) ---
        console.log("📱 [MockAndroid] Native is now fetching records from server...");
        try {
            const response = await mockInternalApiService.getRecordList(taskId);

            console.log("📱 [MockAndroid] Server Data fetched. Calling window.onRecordsReceived...");
            if (window.onRecordsReceived) {
                window.onRecordsReceived({
                   code: 200,
                   msg: "服务器记录同步完成 (共" + response.data.length + "条)",
                   data: response.data
                });
            }
        } catch (e) {
            console.error("Native Fetch Error", e);
             if (window.onRecordsReceived) {
                window.onRecordsReceived({ 
                    code: 500, 
                    msg: "网络同步失败", 
                    data: localRecords 
                });
            }
        }
      }
    };
  }
};
// ==================================================================================
// [MOCK END]
// ==================================================================================