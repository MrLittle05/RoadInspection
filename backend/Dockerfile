# 1. 指定轻量级的 Node.js 基础镜像
FROM node:20-bookworm-slim

# 2. 在容器内创建并设置工作目录
WORKDIR /usr/src/app

# 3. 优先复制 package.json 和 package-lock.json
# 这是一个经典的优化：利用 Docker 缓存层，只要依赖没变，下次构建会极快
COPY package*.json ./

# 4. 安装项目依赖 (推荐使用 npm ci，确保依赖版本与 lock 文件严格一致)
RUN npm ci

# 5. 将当前目录下的所有源代码复制到容器的工作目录中
COPY . .

# 6. 声明容器将暴露的端口 (与你代码里的 3000 一致)
EXPOSE 3000

# 7. 定义容器启动时执行的默认命令
CMD ["node", "app.js"]