/**
 * @file app.test.js
 * @description 后端 API 集成测试 (Fixed)
 */

import { jest } from "@jest/globals";
import "dotenv/config"; // 加载环境变量
import { MongoMemoryServer } from "mongodb-memory-server";
import mongoose from "mongoose";
import request from "supertest";

// Mock 阿里云 OSS
jest.mock("../utils/oss_helper.js", () => ({
  getStsToken: jest.fn().mockResolvedValue({
    AccessKeyId: "mock-id",
    AccessKeySecret: "mock-secret",
    SecurityToken: "mock-token",
    Expiration: "2099-01-01T00:00:00Z",
  }),
}));

import { app } from "../app.js";
import { Record, Task, User } from "../model/models.js";

let mongoServer;
let globalToken; // ✅ 新增：全局 Token，用于鉴权

// ============================================================
// 测试生命周期钩子
// ============================================================

beforeAll(async () => {
  mongoServer = await MongoMemoryServer.create();
  const uri = mongoServer.getUri();
  if (mongoose.connection.readyState !== 0) {
    await mongoose.disconnect();
  }
  await mongoose.connect(uri);

  // ✅ 新增：初始化一个全局用户并获取 Token，供后续需要鉴权的接口使用
  // 我们直接调用 app 的注册接口来生成合法的 Token
  const res = await request(app.callback()).post("/api/auth/register").send({
    username: "global_tester",
    password: "password123",
    role: "inspector",
  });
  globalToken = res.body.data.accessToken;
}, 60000); // 60秒超时

afterEach(async () => {
  const collections = mongoose.connection.collections;
  for (const key in collections) {
    // ⚠️ 注意：不要清空 User 表，否则 globalToken 对应的用户也没了，导致鉴权失败
    // 或者每次 afterEach 后重新生成 token。
    // 这里采取简单策略：只清空 Task 和 Record，保留 User
    if (key !== "users") {
      await collections[key].deleteMany({});
    }
  }
});

afterAll(async () => {
  await mongoose.disconnect();
  await mongoServer.stop();
});

// ============================================================
// 测试套件
// ============================================================

describe("🚀 Road Inspection API Integration Tests", () => {
  // ----------------------------------------------------------
  // 1. Auth Module (Auth 模块本身不需要 Bearer Token)
  // ----------------------------------------------------------
  describe("🔐 Auth Module", () => {
    it("POST /api/auth/register - 应该成功注册新用户", async () => {
      const res = await request(app.callback())
        .post("/api/auth/register")
        .send({
          username: "inspector_zhang",
          password: "password123",
          role: "inspector",
        });
      expect(res.status).toBe(200);
      expect(res.body.data.username).toBe("inspector_zhang");
    });

    it("POST /api/auth/register - 如果用户名已存在应拒绝", async () => {
      // User 表现在保留了数据，所以无需手动 create 重复用户，直接用上面那个
      const res = await request(app.callback())
        .post("/api/auth/register")
        .send({ username: "inspector_zhang", password: "123" });

      expect(res.body.code).toBe(409);
      expect(res.body.message).toBe("用户名已被占用");
    });

    it("POST /api/auth/login - 输入正确密码应登录成功", async () => {
      // 先注册
      await request(app.callback()).post("/api/auth/register").send({
        username: "login_test",
        password: "securePass",
      });
      // 再登录
      const res = await request(app.callback())
        .post("/api/auth/login")
        .send({ username: "login_test", password: "securePass" });
      expect(res.status).toBe(200);
    });

    it("POST /api/auth/login - 密码错误应拒绝登录", async () => {
      await request(app.callback()).post("/api/auth/register").send({
        username: "wrong_pass_user",
        password: "correctPass",
      });
      const res = await request(app.callback())
        .post("/api/auth/login")
        .send({ username: "wrong_pass_user", password: "WRONG_PASS" });
      expect(res.body.code).toBe(401);
    });
  });

  // ----------------------------------------------------------
  // 2. OSS Module (需要鉴权)
  // ----------------------------------------------------------
  describe("☁️ OSS Module", () => {
    it("GET /api/oss/sts - 应该返回模拟的 Token", async () => {
      const res = await request(app.callback())
        .get("/api/oss/sts")
        .set("Authorization", `Bearer ${globalToken}`); // ✅ 添加鉴权头

      expect(res.status).toBe(200);
      expect(res.body.data.AccessKeyId).toBe("mock-id");
    });
  });

  // ----------------------------------------------------------
  // 3. Task Module (需要鉴权)
  // ----------------------------------------------------------
  describe("📋 Task Module", () => {
    const mockInspectorId = new mongoose.Types.ObjectId();

    const taskData = {
      taskId: "uuid-task-001",
      title: "周五高新南路巡检",
      inspectorId: mockInspectorId,
      startTime: 1700000000000,
    };

    it("POST /api/task/create - 重复提交相同 TaskId 不应创建多条数据", async () => {
      const res1 = await request(app.callback())
        .post("/api/task/create")
        .set("Authorization", `Bearer ${globalToken}`) // ✅ 添加鉴权头
        .send(taskData);
      expect(res1.body.code).toBe(200);

      const res2 = await request(app.callback())
        .post("/api/task/create")
        .set("Authorization", `Bearer ${globalToken}`) // ✅ 添加鉴权头
        .send(taskData);
      expect(res2.body.code).toBe(200);

      const count = await Task.countDocuments({ taskId: taskData.taskId });
      expect(count).toBe(1);
    });

    it("POST /api/task/finish - 应该更新任务状态", async () => {
      await Task.create(taskData);
      const res = await request(app.callback())
        .post("/api/task/finish")
        .set("Authorization", `Bearer ${globalToken}`) // ✅ 添加鉴权头
        .send({ taskId: taskData.taskId, endTime: 1700000999999 });

      expect(res.body.code).toBe(200);
      const finishedTask = await Task.findOne({ taskId: taskData.taskId });
      expect(finishedTask.isFinished).toBe(true);
    });

    it("GET /api/task/list - 应该只返回指定用户的任务且按时间倒序排列", async () => {
      const userA = new mongoose.Types.ObjectId();
      const userB = new mongoose.Types.ObjectId();

      await Task.create({
        taskId: "task-a-old",
        title: "User A Old Task",
        inspectorId: userA,
        startTime: 1000,
      });
      await Task.create({
        taskId: "task-a-new",
        title: "User A New Task",
        inspectorId: userA,
        startTime: 2000,
      });
      await Task.create({
        taskId: "task-b-001",
        title: "User B Task",
        inspectorId: userB,
        startTime: 1500,
      });

      const res = await request(app.callback())
        .get(`/api/task/list?userId=${userA.toHexString()}`)
        .set("Authorization", `Bearer ${globalToken}`); // ✅ 添加鉴权头

      expect(res.status).toBe(200);
      expect(res.body.data).toHaveLength(2);
      expect(res.body.data[0].taskId).toBe("task-a-new");
      expect(res.body.data[1].taskId).toBe("task-a-old");
    });
  });

  // ----------------------------------------------------------
  // 4. Record Module (需要鉴权)
  // ----------------------------------------------------------
  describe("📷 Record Module", () => {
    it("POST /api/record/submit - 应该正确存储 GeoJSON [Lng, Lat] 格式", async () => {
      const recordData = {
        recordId: "rec-uuid-001", // ✅ 补充 recordId (Schema Required)
        taskId: "uuid-task-001",
        serverUrl: "http://oss/img.jpg",
        captureTime: Date.now(),
        latitude: 30.5,
        longitude: 104.1,
        address: "成都市武侯区",
      };
      const res = await request(app.callback())
        .post("/api/record/submit")
        .set("Authorization", `Bearer ${globalToken}`) // ✅ 添加鉴权头
        .send(recordData);

      expect(res.body.code).toBe(200);
      const savedRecord = await Record.findOne({ taskId: "uuid-task-001" });
      expect(savedRecord.location.coordinates[0]).toBe(104.1);
      expect(savedRecord.location.coordinates[1]).toBe(30.5);
    });

    it("GET /api/record/list - 应该根据 taskId 获取记录", async () => {
      const tid = "uuid-task-query";
      const mockLocation = { type: "Point", coordinates: [104.0, 30.0] };

      // ✅ 修复：补充 recordId 字段
      await Record.create({
        recordId: "rec-1",
        taskId: tid,
        captureTime: 1000,
        serverUrl: "url1",
        location: mockLocation,
        rawLat: 30.0,
        rawLng: 104.0,
      });
      await Record.create({
        recordId: "rec-2",
        taskId: tid,
        captureTime: 2000,
        serverUrl: "url2",
        location: mockLocation,
        rawLat: 30.0,
        rawLng: 104.0,
      });

      const res = await request(app.callback())
        .get(`/api/record/list?taskId=${tid}`)
        .set("Authorization", `Bearer ${globalToken}`); // ✅ 添加鉴权头

      expect(res.body.code).toBe(200);
      expect(res.body.data).toHaveLength(2);
    });
  });

  // ----------------------------------------------------------
  // 5. Soft Delete Module (需要鉴权)
  // ----------------------------------------------------------
  describe("🗑️ Soft Delete Module", () => {
    const ownerId = new mongoose.Types.ObjectId();
    const otherUserId = new mongoose.Types.ObjectId();
    const taskId = "task-delete-test";

    beforeEach(async () => {
      await Task.create({
        taskId: taskId,
        title: "Task to delete",
        inspectorId: ownerId,
        startTime: 1000,
        deletedAt: null,
      });

      const mockLocation = { type: "Point", coordinates: [104.0, 30.0] };
      await Record.create({
        recordId: "rec-1",
        taskId: taskId,
        serverUrl: "url",
        captureTime: 1000,
        location: mockLocation,
        rawLat: 30,
        rawLng: 104,
        deletedAt: null,
      });
    });

    it("DELETE /api/task/:taskId - 缺少 userId 应返回 400", async () => {
      const res = await request(app.callback())
        .delete(`/api/task/${taskId}`)
        .set("Authorization", `Bearer ${globalToken}`); // ✅ 添加鉴权头

      expect(res.body.code).toBe(400);
      expect(res.body.message).toContain("userId 不能为空");
    });

    it("DELETE /api/task/:taskId - 非所有者无法删除 (返回 404)", async () => {
      const res = await request(app.callback())
        .delete(`/api/task/${taskId}?userId=${otherUserId.toHexString()}`)
        .set("Authorization", `Bearer ${globalToken}`); // ✅ 添加鉴权头

      expect(res.body.code).toBe(404);
      const task = await Task.findOne({ taskId });
      expect(task.deletedAt).toBeNull();
    });

    it("DELETE /api/task/:taskId - 正常删除应软删除任务及级联记录", async () => {
      const res = await request(app.callback())
        .delete(`/api/task/${taskId}?userId=${ownerId.toHexString()}`)
        .set("Authorization", `Bearer ${globalToken}`); // ✅ 添加鉴权头

      expect(res.body.code).toBe(200);
      expect(res.body.message).toBe("删除成功");

      const task = await Task.findOne({ taskId });
      expect(task.deletedAt).not.toBeNull();

      const record = await Record.findOne({ recordId: "rec-1" });
      expect(record.deletedAt).not.toBeNull();
    });

    it("GET /api/task/list - 已软删除的任务不应出现在列表中", async () => {
      await request(app.callback())
        .delete(`/api/task/${taskId}?userId=${ownerId.toHexString()}`)
        .set("Authorization", `Bearer ${globalToken}`); // ✅ 添加鉴权头

      const res = await request(app.callback())
        .get(`/api/task/list?userId=${ownerId.toHexString()}`)
        .set("Authorization", `Bearer ${globalToken}`); // ✅ 添加鉴权头

      expect(res.body.code).toBe(200);
      const found = res.body.data.find((t) => t.taskId === taskId);
      expect(found).toBeUndefined();
    });

    it("GET /api/record/list - 已软删除的记录不应出现在列表中", async () => {
      await request(app.callback())
        .delete(`/api/task/${taskId}?userId=${ownerId.toHexString()}`)
        .set("Authorization", `Bearer ${globalToken}`); // ✅ 添加鉴权头

      const res = await request(app.callback())
        .get(`/api/record/list?taskId=${taskId}`)
        .set("Authorization", `Bearer ${globalToken}`); // ✅ 添加鉴权头

      expect(res.body.code).toBe(200);
      expect(res.body.data).toHaveLength(0);
    });
  });
});
