import { User } from '../types';

// Simulate Database
interface DbUser {
    id: string;
    username: string;
    hashedPassword: string; // In mock, we just prefix it, but act like it's hashed
    role: 'admin' | 'inspector';
    deletedAt: number | null;
}

// Initial Mock DB
let USERS_DB: DbUser[] = [
    {
        id: 'user_default',
        username: 'inspector',
        hashedPassword: 'hashed_123456', // password is '123456'
        role: 'inspector',
        deletedAt: null
    }
];

// Helper to simulate network delay
const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

// Helper to simulate bcrypt compare
const checkPassword = (plain: string, hashed: string) => {
    return `hashed_${plain}` === hashed;
};

// Helper to simulate bcrypt hash
const hashPassword = (plain: string) => {
    return `hashed_${plain}`;
};

interface ApiResponse<T> {
    code: number;
    message: string;
    data?: T;
}

export const mockAuthService = {
    login: async (username: string, password: string): Promise<ApiResponse<User>> => {
        await delay(800); // Simulate network latency

        const user = USERS_DB.find(u => u.username === username);

        // 2. 账号不存在校验
        if (!user) {
            return { code: 401, message: "用户名或密码错误" };
        }

        // 3. 软删除校验
        if (user.deletedAt) {
            return { code: 403, message: "账号已停用" };
        }

        // 4. 密码比对
        if (!checkPassword(password, user.hashedPassword)) {
            return { code: 401, message: "用户名或密码错误" };
        }

        // 5. 返回结果
        return {
            code: 200,
            message: "登录成功",
            data: {
                id: user.id,
                username: user.username,
                role: user.role
            }
        };
    },

    register: async (username: string, password: string): Promise<ApiResponse<User>> => {
        await delay(1000);

        // 2. 检查用户名是否已存在
        const existingUser = USERS_DB.find(u => u.username === username);
        if (existingUser) {
            return { code: 409, message: "用户名已被占用" };
        }

        // 3 & 4. 创建用户
        const newUser: DbUser = {
            id: `user_${Date.now()}`,
            username,
            hashedPassword: hashPassword(password),
            role: 'inspector',
            deletedAt: null
        };

        USERS_DB.push(newUser);

        return {
            code: 200,
            message: "注册成功",
            data: {
                id: newUser.id,
                username: newUser.username,
                role: newUser.role
            }
        };
    },

    updateProfile: async (id: string, newUsername?: string, newPassword?: string): Promise<ApiResponse<User>> => {
        await delay(800);

        const userIndex = USERS_DB.findIndex(u => u.id === id);
        if (userIndex === -1) {
            return { code: 404, message: "用户不存在" };
        }

        // Validate username uniqueness if changing
        if (newUsername && newUsername !== USERS_DB[userIndex].username) {
             const existingUser = USERS_DB.find(u => u.username === newUsername);
             if (existingUser) {
                 return { code: 409, message: "用户名已被占用" };
             }
             USERS_DB[userIndex].username = newUsername;
        }

        if (newPassword) {
            USERS_DB[userIndex].hashedPassword = hashPassword(newPassword);
        }

        const updatedUser = USERS_DB[userIndex];

        return {
            code: 200,
            message: "资料修改成功",
            data: {
                id: updatedUser.id,
                username: updatedUser.username,
                role: updatedUser.role
            }
        };
    }
};