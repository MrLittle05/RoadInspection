import jwt from 'jsonwebtoken'

/**
 * 生成双 Token
 * @param {Object} user - 用户文档对象
 * @returns {Object} { accessToken, refreshToken }
 */
export const generateTokens = (user) => {
  // Access Token: 包含业务所需的常用字段 (ID, Role)
  const accessToken = jwt.sign(
    { id: user.id, role: user.role, username: user.username },
    process.env.JWT_ACCESS_SECRET,
    { expiresIn: process.env.ACCESS_TOKEN_EXPIRES },
  )

  // Refresh Token: 仅包含 ID，用于查库验证
  const refreshToken = jwt.sign(
    { id: user.id },
    process.env.JWT_REFRESH_SECRET,
    {
      expiresIn: process.env.REFRESH_TOKEN_EXPIRES,
    },
  )

  return { accessToken, refreshToken }
}
