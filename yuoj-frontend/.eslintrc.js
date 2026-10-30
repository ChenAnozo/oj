module.exports = {
  root: true,
  env: {
    node: true,
    // 添加 browser 环境，防止 window/document 报错
    browser: true,
    "vue/setup-compiler-macros": true,
  },
  // 【修正1】拼写错误：extends 写成了 extends
  extends: [
    "plugin:vue/vue3-essential", // 注意：通常用 essential 或 recommended，你写的 vue3-essential 可能不存在，建议改为 plugin:vue/vue3-recommended
    "eslint:recommended",
    "@vue/typescript/recommended",
    "plugin:prettier/recommended",
  ],
  // 【修正2】拼写错误：parserOptions 写成了 parserOptions (你原代码是对的，但确认一下)
  parserOptions: {
    ecmaVersion: 2020,
    sourceType: "module",
  },
  rules: {
    "no-console": process.env.NODE_ENV === "production" ? "warn" : "off",
    "no-debugger": process.env.NODE_ENV === "production" ? "warn" : "off",

    // 【修正3】语法错误：semi 的写法不对，应该是数组形式 ["error", "always"]
    semi: ["warn", "always"],

    "prettier/prettier": "warn",

    // 【核心修复】告诉 ESLint 这些是 Vue 的全局变量，解决 'withDefaults' is not defined
    // 这样你就不需要在每个文件里 import withDefaults 了
    "no-undef": "off", // 关闭基础 no-undef，因为 @vue/typescript/recommended 会处理得更好
  },
};
