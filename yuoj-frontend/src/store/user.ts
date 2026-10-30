// initial state
import ACCESS_ENUM from "@/access/accessEnum";
import { StoreOptions } from "vuex";
import { UserControllerService } from "../../generated";

export default {
  namespaced: true,
  state: () => ({
    loginUser: {
      userName: "未登录"
    }
  }),
  actions: {
    async getLoginUser({ commit }) {
      // 从远程请求获取登录信息
      try {
        const res = await UserControllerService.getLoginUserUsingGet();
        if (res.code === 0) {
          commit("updateUser", res.data);
        } else {
          commit("updateUser", { userName: "未登录", userRole: ACCESS_ENUM.NOT_LOGIN });
        }
      } catch {
        commit("updateUser", { userName: "未登录", userRole: ACCESS_ENUM.NOT_LOGIN });
      }
    },

    async userLoginOut({ commit }) {
      const res = await UserControllerService.userLogoutUsingPost();
      if (res.code === 0) {
        commit("updateUser", {
          userName: "未登录",
          userRole: ACCESS_ENUM.NOT_LOGIN
        });
      } else {
        throw new Error(res.message || "退出失败");
      }
    }
  },
  mutations: {
    updateUser(state, payload) {
      state.loginUser = payload;
    }
  }
} as StoreOptions<any>;
