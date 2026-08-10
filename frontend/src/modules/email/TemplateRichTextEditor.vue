<script setup lang="ts">
import {
  ArrowUturnLeftIcon, ArrowUturnRightIcon, BoldIcon, LinkIcon, ListBulletIcon,
} from '@heroicons/vue/24/outline'
import Image from '@tiptap/extension-image'
import StarterKit from '@tiptap/starter-kit'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import { onBeforeUnmount, watch } from 'vue'

const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const editor = useEditor({
  content: props.modelValue,
  extensions: [
    StarterKit.configure({
      link: { openOnClick: false, autolink: false, defaultProtocol: 'https' },
    }),
    Image.configure({ allowBase64: false }),
  ],
  editorProps: {
    attributes: {
      class: 'min-h-72 px-4 py-4 text-sm/7 text-slate-800 outline-none',
      'aria-label': '邮件 HTML 正文编辑器',
    },
  },
  onUpdate: ({ editor: current }) => emit('update:modelValue', current.getHTML()),
})

watch(() => props.modelValue, (value) => {
  if (editor.value && editor.value.getHTML() !== value) editor.value.commands.setContent(value, { emitUpdate: false })
})

onBeforeUnmount(() => editor.value?.destroy())

function setLink(): void {
  if (!editor.value) return
  const previous = editor.value.getAttributes('link').href as string | undefined
  const href = globalThis.window.prompt('输入完整的 HTTPS 链接或选择变量 {{paper_url}} / {{unsubscribe_url}}', previous ?? 'https://')
  if (href === null) return
  if (!href) editor.value.chain().focus().extendMarkRange('link').unsetLink().run()
  else editor.value.chain().focus().extendMarkRange('link').setLink({ href }).run()
}

function insertContent(content: string): void {
  editor.value?.chain().focus().insertContent(content).run()
}

function insertImage(src: string, alt: string): void {
  editor.value?.chain().focus().setImage({ src, alt }).run()
}

defineExpose({ insertContent, insertImage })
</script>

<template>
  <div class="overflow-hidden rounded-lg bg-white ring-1 ring-slate-300 focus-within:ring-2 focus-within:ring-brand-500">
    <div
      v-if="editor"
      class="flex flex-wrap items-center gap-1 border-b border-slate-200 bg-slate-50 px-2 py-2"
      aria-label="富文本工具栏"
    >
      <button
        type="button"
        :class="['editor-tool', editor.isActive('bold') && 'editor-tool-active']"
        aria-label="粗体"
        @click="editor.chain().focus().toggleBold().run()"
      >
        <BoldIcon class="size-4" />
      </button>
      <button
        type="button"
        :class="['editor-tool font-serif italic', editor.isActive('italic') && 'editor-tool-active']"
        aria-label="斜体"
        @click="editor.chain().focus().toggleItalic().run()"
      >
        I
      </button>
      <button
        type="button"
        :class="['editor-tool underline', editor.isActive('underline') && 'editor-tool-active']"
        aria-label="下划线"
        @click="editor.chain().focus().toggleUnderline().run()"
      >
        U
      </button>
      <span class="mx-1 h-5 w-px bg-slate-200" />
      <button
        type="button"
        :class="['editor-tool', editor.isActive('heading', { level: 2 }) && 'editor-tool-active']"
        aria-label="二级标题"
        @click="editor.chain().focus().toggleHeading({ level: 2 }).run()"
      >
        H2
      </button>
      <button
        type="button"
        :class="['editor-tool', editor.isActive('bulletList') && 'editor-tool-active']"
        aria-label="无序列表"
        @click="editor.chain().focus().toggleBulletList().run()"
      >
        <ListBulletIcon class="size-4" />
      </button>
      <button
        type="button"
        :class="['editor-tool', editor.isActive('link') && 'editor-tool-active']"
        aria-label="链接"
        @click="setLink"
      >
        <LinkIcon class="size-4" />
      </button>
      <span class="mx-1 h-5 w-px bg-slate-200" />
      <button
        type="button"
        class="editor-tool"
        aria-label="撤销"
        :disabled="!editor.can().undo()"
        @click="editor.chain().focus().undo().run()"
      >
        <ArrowUturnLeftIcon class="size-4" />
      </button>
      <button
        type="button"
        class="editor-tool"
        aria-label="重做"
        :disabled="!editor.can().redo()"
        @click="editor.chain().focus().redo().run()"
      >
        <ArrowUturnRightIcon class="size-4" />
      </button>
    </div>
    <EditorContent
      v-if="editor"
      :editor="editor"
    />
  </div>
</template>

<style scoped>
.editor-tool {
  display: inline-grid;
  min-width: 2.25rem;
  min-height: 2.25rem;
  place-items: center;
  border-radius: 0.375rem;
  color: #64748b;
  font-size: 0.75rem;
  font-weight: 700;
}
.editor-tool:hover, .editor-tool-active { background: #e0e7ff; color: #4058d8; }
.editor-tool:disabled { cursor: not-allowed; opacity: .35; }
:deep(.tiptap p) { margin: .5rem 0; }
:deep(.tiptap h2) { margin: 1rem 0 .5rem; font-size: 1.25rem; font-weight: 650; }
:deep(.tiptap ul) { margin: .5rem 0; list-style: disc; padding-left: 1.5rem; }
:deep(.tiptap a) { color: #4058d8; text-decoration: underline; }
:deep(.tiptap img) { max-width: 100%; border-radius: .5rem; }
</style>
