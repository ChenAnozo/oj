// Only return to application routes; reject protocol-relative and backslash URLs.
export function safeReturnPath(value: unknown, fallback = "/questions"): string {
  if (
    typeof value !== "string" ||
    !value.startsWith("/") ||
    value.startsWith("//") ||
    Array.from(value).some((char) => char === "\\" || char.charCodeAt(0) <= 32)
  )
    return fallback;
  const path = value.split(/[?#]/)[0];
  if (path === "/user" || path.startsWith("/user/")) return fallback;
  return value;
}
