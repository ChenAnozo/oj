<template><div ref="container" class="code-editor" aria-label="代码编辑器" /></template>
<script setup lang="ts">
import * as monaco from "monaco-editor/esm/vs/editor/editor.api";
import "monaco-editor/esm/vs/basic-languages/java/java.contribution";
import "monaco-editor/esm/vs/basic-languages/cpp/cpp.contribution";
import "monaco-editor/esm/vs/basic-languages/go/go.contribution";
import { onMounted, onBeforeUnmount, ref, watch } from "vue";
const props = withDefaults(defineProps<{ value: string; language?: string; handleChange: (value: string) => void }>(), {
  language: "java"
});
const container = ref<HTMLElement>();
let editor: monaco.editor.IStandaloneCodeEditor;
let listener: monaco.IDisposable;
watch(
  () => props.language,
  (language) => {
    const model = editor?.getModel();
    if (model) monaco.editor.setModelLanguage(model, language);
  }
);
watch(
  () => props.value,
  (value) => {
    if (editor && value !== editor.getValue()) editor.setValue(value || "");
  }
);
onMounted(() => {
  editor = monaco.editor.create(container.value, {
    value: props.value || "",
    language: props.language,
    automaticLayout: true,
    minimap: { enabled: false },
    theme: "vs-dark",
    fontSize: 14,
    padding: { top: 18 },
    scrollBeyondLastLine: false,
    tabSize: 4
  });
  listener = editor.onDidChangeModelContent(() => props.handleChange(editor.getValue()));
});
onBeforeUnmount(() => {
  listener?.dispose();
  const model = editor?.getModel();
  editor?.dispose();
  model?.dispose();
});
</script>
<style scoped>
.code-editor {
  min-height: 400px;
  height: 58vh;
  overflow: hidden;
  border-radius: 10px;
}
</style>
