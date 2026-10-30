const { defineConfig } = require("@vue/cli-service");
const MonacoWebpackPlugin = require("monaco-editor-webpack-plugin");

module.exports = defineConfig({
  transpileDependencies: true,
  devServer: process.env.VUE_APP_DEV_PROXY_TARGET
    ? {
        client: {
          overlay: {
            warnings: false,
            errors: true,
            runtimeErrors: (error) =>
              ![
                "ResizeObserver loop completed with undelivered notifications.",
                "ResizeObserver loop limit exceeded",
              ].includes(error.message),
          },
        },
        proxy: {
          "/api": {
            target: process.env.VUE_APP_DEV_PROXY_TARGET,
            changeOrigin: true,
            ws: true,
          },
        },
      }
    : {},
  chainWebpack(config) {
    config
      .plugin("monaco")
      .use(new MonacoWebpackPlugin({ languages: ["java", "cpp", "go"] }));
  },
});
