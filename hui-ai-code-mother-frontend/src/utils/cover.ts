/**
 * 规范化封面图片 URL
 *
 * 后端存储的封面地址可能不带协议头（如 ai-project-xxx.cos.ap-guangzhou.myqcloud.com/screenshots/xx.jpg），
 * 直接作为 img 的 src 会被浏览器当作相对路径解析，导致图片裂图。
 * 这里统一补全 https 协议；无封面时返回空字符串，由调用方展示默认占位图。
 *
 * @param cover 原始封面地址
 * @returns 带协议的完整地址，无封面时返回空字符串
 */
export const normalizeCoverUrl = (cover?: string): string => {
  if (!cover) {
    return ''
  }
  const trimmed = cover.trim()
  if (!trimmed) {
    return ''
  }
  // 已带 http/https 协议，原样返回
  if (/^https?:\/\//i.test(trimmed)) {
    return trimmed
  }
  // 兼容 //host/path 形式的协议相对地址
  if (trimmed.startsWith('//')) {
    return `https:${trimmed}`
  }
  return `https://${trimmed}`
}
