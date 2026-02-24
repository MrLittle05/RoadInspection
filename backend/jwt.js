import jwt from "jsonwebtoken";
const { sign, verify } = jwt;

const secret = "test_secret";

// 1. 制造一个过期的 Token (1秒后过期)
const expiredToken = sign({ foo: "bar" }, secret, { expiresIn: "-1s" });

// 2. 制造一个被篡改的 Token
const badToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.invalid_signature";

console.log("--- 测试过期 ---");
try {
  verify(expiredToken, secret);
} catch (err) {
  console.log(`Error Name: ${err.name}`); // 输出: TokenExpiredError
  console.log(`Message:  ${err.message}`); // 输出: jwt expired
}

console.log("\n--- 测试无效 ---");
try {
  verify(badToken, secret);
} catch (err) {
  console.log(`Error Name: ${err.name}`); // 输出: JsonWebTokenError
  console.log(`Message:  ${err.message}`); // 输出: invalid token
}
