/**
 * 环境变量配置说明
 *
 * 在项目根目录创建 .env.local 或 .env.development 文件，并添加以下配置：
 *
 * # 应用部署域名
 * VITE_DEPLOY_DOMAIN=http://localhost
 *
 * # API 基础地址（通过同源代理访问，支持 iframe 可视化编辑）
 * VITE_API_BASE_URL=/api
 *
 * 生产环境可以创建 .env.production 文件：
 *
 * # 生产环境配置示例；服务器将 /api 反向代理到后端
 * VITE_DEPLOY_DOMAIN=https://your-domain.com
 * VITE_API_BASE_URL=/api
 */

export {}
