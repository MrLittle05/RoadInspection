/**
 * @file app.test.js
 * @description 后端 API 集成测试
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

// ============================================================
// 测试生命周期钩子
// ============================================================

beforeAll(async () => {
  mongoServer = await MongoMemoryServer.create();
  const uri = mongoServer.getUri();
  await mongoose.disconnect();
  await mongoose.connect(uri);
}, 60000); // 60秒超时

afterEach(async () => {
  const collections = mongoose.connection.collections;
  for (const key in collections) {
    await collections[key].deleteMany({});
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
  // 1. Auth Module
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
      await User.create({ username: "existing_user", hashedPassword: "xxx" });
      const res = await request(app.callback())
        .post("/api/auth/register")
        .send({ username: "existing_user", password: "123" });

      expect(res.body.code).toBe(409);
      // 【修正】直接精确匹配字符串，避免正则问题
      expect(res.body.message).toBe("用户名已被占用");
    });

    it("POST /api/auth/login - 输入正确密码应登录成功", async () => {
      await request(app.callback()).post("/api/auth/register").send({
        username: "login_test",
        password: "securePass",
      });
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
  // 2. OSS Module
  // ----------------------------------------------------------
  describe("☁️ OSS Module", () => {
    it("GET /api/oss/sts - 应该返回模拟的 Token", async () => {
      const res = await request(app.callback()).get("/api/oss/sts");
      expect(res.status).toBe(200);
      expect(res.body.data.AccessKeyId).toBe("mock-id");
    });
  });

  // ----------------------------------------------------------
  // 3. Task Module
  // ----------------------------------------------------------
  describe("📋 Task Module", () => {
    // 【修正】创建一个真实的 ObjectId，而不是用 "user-001" 这种字符串
    // 否则 mongoose 校验会失败 (CastError)
    const mockInspectorId = new mongoose.Types.ObjectId();

    const taskData = {
      taskId: "uuid-task-001",
      title: "周五高新南路巡检",
      inspectorId: mockInspectorId, // 这里必须是 ObjectId 对象
      startTime: 1700000000000,
    };

    it("POST /api/task/create - 重复提交相同 TaskId 不应创建多条数据", async () => {
      const res1 = await request(app.callback())
        .post("/api/task/create")
        .send(taskData);
      expect(res1.body.code).toBe(200);

      const res2 = await request(app.callback())
        .post("/api/task/create")
        .send(taskData);
      expect(res2.body.code).toBe(200);

      const count = await Task.countDocuments({ taskId: taskData.taskId });
      expect(count).toBe(1);
    });

    it("POST /api/task/finish - 应该更新任务状态", async () => {
      await Task.create(taskData);
      const res = await request(app.callback())
        .post("/api/task/finish")
        .send({ taskId: taskData.taskId, endTime: 1700000999999 });

      expect(res.body.code).toBe(200);
      const finishedTask = await Task.findOne({ taskId: taskData.taskId });
      expect(finishedTask.isFinished).toBe(true);
    });
  });

  // ----------------------------------------------------------
  // 4. Record Module
  // ----------------------------------------------------------
  describe("📷 Record Module", () => {
    it("POST /api/record/submit - 应该正确存储 GeoJSON [Lng, Lat] 格式", async () => {
      const recordData = {
        taskId: "uuid-task-001",
        serverUrl: "http://oss/img.jpg",
        captureTime: Date.now(),
        latitude: 30.5,
        longitude: 104.1,
        address: "成都市武侯区",
      };
      const res = await request(app.callback())
        .post("/api/record/submit")
        .send(recordData);

      expect(res.body.code).toBe(200);
      const savedRecord = await Record.findOne({ taskId: "uuid-task-001" });
      expect(savedRecord.location.coordinates[0]).toBe(104.1);
      expect(savedRecord.location.coordinates[1]).toBe(30.5);
    });

    it("GET /api/record/list - 应该根据 taskId 获取记录", async () => {
      const tid = "uuid-task-query";

      // 【修正】手动 create 数据时，必须包含 required 的 location 字段
      const mockLocation = { type: "Point", coordinates: [104.0, 30.0] };

      await Record.create({
        taskId: tid,
        captureTime: 1000,
        serverUrl: "url1",
        location: mockLocation,
        rawLat: 30.0,
        rawLng: 104.0,
      });
      await Record.create({
        taskId: tid,
        captureTime: 2000,
        serverUrl: "url2",
        location: mockLocation,
        rawLat: 30.0,
        rawLng: 104.0,
      });

      const res = await request(app.callback()).get(
        `/api/record/list?taskId=${tid}`,
      );

      expect(res.body.code).toBe(200);
      expect(res.body.data).toHaveLength(2);
    });
  });
});
