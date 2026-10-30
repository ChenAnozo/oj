import { reactive, ref, shallowRef, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

// URL is the applied filter state; editing fields alone never triggers a request.
export function usePagedList<T>(
  fields: string[],
  fetchPage: (
    query: Record<string, unknown>
  ) => PromiseLike<{ code?: number; message?: string; data?: { records?: T[]; total?: number } }>
) {
  const route = useRoute();
  const router = useRouter();
  const filters = reactive<Record<string, string>>({});
  const rows = shallowRef<T[]>([]);
  const total = ref(0);
  const current = ref(1);
  const loading = ref(false);
  const error = ref("");
  let requestId = 0;
  const load = async () => {
    const id = ++requestId;
    loading.value = true;
    error.value = "";
    try {
      const query: Record<string, unknown> = {
        current: current.value,
        pageSize: 10,
        sortField: "createTime",
        sortOrder: "descend"
      };
      fields.forEach((key) => {
        const value = route.query[key];
        if (typeof value === "string" && value.trim()) {
          query[key] =
            key === "tags"
              ? value
                  .split(/[,，]/)
                  .map((v) => v.trim())
                  .filter(Boolean)
              : key === "status"
              ? Number(value)
              : value;
        }
      });
      const res = await fetchPage(query);
      if (id !== requestId) return;
      if (res.code !== 0) throw new Error(res.message || "请求失败");
      rows.value = res.data?.records || [];
      total.value = Number(res.data?.total || 0);
    } catch (e) {
      if (id === requestId) {
        error.value = e instanceof Error ? e.message : "网络异常，请稍后重试";
        rows.value = [];
        total.value = 0;
      }
    } finally {
      if (id === requestId) loading.value = false;
    }
  };
  watch(
    () => route.fullPath,
    () => {
      fields.forEach(
        (key) => (filters[key] = typeof route.query[key] === "string" ? (route.query[key] as string) : "")
      );
      const page = Number(route.query.page);
      current.value = Number.isSafeInteger(page) && page > 0 ? page : 1;
      load();
    },
    { immediate: true }
  );
  const navigate = async (page: number, reset = false) => {
    const query: Record<string, string> = {};
    fields.forEach((key) => {
      if (!reset && filters[key]?.trim()) query[key] = filters[key].trim();
    });
    if (page > 1) query.page = String(page);
    const target = { path: route.path, query };
    if (router.resolve(target).fullPath === route.fullPath) await load();
    else await router.push(target);
  };
  return {
    filters,
    rows,
    total,
    current,
    loading,
    error,
    load,
    search: () => navigate(1),
    reset: () => navigate(1, true),
    changePage: (page: number) => navigate(page)
  };
}
