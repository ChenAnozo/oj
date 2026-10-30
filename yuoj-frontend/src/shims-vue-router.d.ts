// 声明一个名为 "vue-router" 的模块
declare module "vue-router" {
  // 从 "vue-router/dist/vue-router" 导入一些类型
  import type {
    RouteRecordRaw, // 路由记录的原始类型，用于定义路由配置
    Router, // Vue Router 的实例类型
    RouterOptions // 创建路由器时的选项类型
  } from "vue-router/dist/vue-router";

  // 创建一个路由器实例的函数，接受路由选项作为参数并返回路由器实例
  export function createRouter(options: RouterOptions): Router;

  // 创建基于 HTML5 History API 的历史记录模式的函数，可选的 base 参数用于指定基础路径
  export function createWebHistory(base?: string): any;

  // 创建基于内存的历史记录模式的函数，可选的 base 参数用于指定基础路径
  export function createMemoryHistory(base?: string): any;

  // 创建基于哈希模式的历史记录模式的函数，可选的 base 参数用于指定基础路径
  export function createWebHashHistory(base?: string): any;

  // 重新导出导入的类型，方便在其他地方使用
  export { RouteRecordRaw, Router, RouterOptions };

  // 导出 "vue-router/dist/vue-router" 中的所有其他类型
  export * from "vue-router/dist/vue-router";
}
