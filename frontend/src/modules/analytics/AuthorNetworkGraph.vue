<script setup lang="ts">
import {
  ArrowsPointingOutIcon,
  PauseIcon,
  PlayIcon,
  SparklesIcon,
} from '@heroicons/vue/24/outline'
import { DataSet, Network, type Edge, type Node, type Options } from 'vis-network/standalone'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import type { AuthorEdge, AuthorNode } from '@/modules/analytics/analytics.types'

const props = withDefaults(defineProps<{
  nodes: AuthorNode[]
  edges: AuthorEdge[]
  loading?: boolean
  error?: string | null
}>(), { loading: false, error: null })

const graphElement = ref<InstanceType<typeof globalThis.HTMLElement> | null>(null)
const selectedAuthorId = ref('')
const reduceMotion = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
const physicsEnabled = ref(!reduceMotion)
const stabilizing = ref(false)
const stabilizationProgress = ref(0)
let network: Network | null = null

const selectedAuthor = computed(() => props.nodes.find((node) => node.id === selectedAuthorId.value) ?? null)
const visibleNodeIds = computed(() => new Set(props.nodes.map((node) => node.id)))
const strongestNeighbors = computed(() => {
  if (!selectedAuthor.value) return []
  return props.edges
    .filter((edge) => edge.source === selectedAuthor.value?.id || edge.target === selectedAuthor.value?.id)
    .map((edge) => {
      const neighborId = edge.source === selectedAuthor.value?.id ? edge.target : edge.source
      return { node: props.nodes.find((node) => node.id === neighborId), count: edge.sharedPaperCount }
    })
    .filter((item): item is { node: AuthorNode; count: number } => Boolean(item.node))
    .sort((left, right) => right.count - left.count || left.node.label.localeCompare(right.node.label))
    .slice(0, 6)
})

const graphOptions = computed<Options>(() => ({
  autoResize: true,
  layout: { improvedLayout: true, randomSeed: 19 },
  interaction: {
    dragNodes: true,
    dragView: true,
    hover: true,
    keyboard: { enabled: true, bindToWindow: false },
    multiselect: false,
    tooltipDelay: 180,
    zoomView: true,
  },
  nodes: {
    shape: 'dot',
    borderWidth: 2,
    borderWidthSelected: 4,
    font: { color: '#334155', face: 'Inter, ui-sans-serif, system-ui', size: 13 },
    scaling: { min: 12, max: 34, label: { enabled: true, min: 11, max: 17 } },
    shadow: { enabled: true, color: 'rgba(15, 23, 42, 0.12)', size: 8, x: 0, y: 3 },
  },
  edges: {
    color: { color: '#b6c2d3', highlight: '#4f6ef7', hover: '#7c8eaa', inherit: false },
    smooth: { enabled: true, type: 'continuous', roundness: 0.2 },
    scaling: { min: 1, max: 7 },
    selectionWidth: 2,
    hoverWidth: 1.5,
  },
  physics: {
    enabled: physicsEnabled.value,
    solver: 'forceAtlas2Based',
    stabilization: { enabled: true, iterations: 500, updateInterval: 25, fit: true },
    forceAtlas2Based: {
      gravitationalConstant: -55,
      centralGravity: 0.015,
      springLength: 115,
      springConstant: 0.075,
      damping: 0.42,
      avoidOverlap: 0.65,
    },
    maxVelocity: 42,
    minVelocity: 0.2,
    timestep: 0.5,
    adaptiveTimestep: true,
  },
}))

function visNodes(): Node[] {
  return props.nodes.map((node) => ({
    id: node.id,
    label: node.label,
    value: Math.max(1, node.paperCount),
    title: `${node.label}\n${node.paperCount} 篇论文 · ${node.collaboratorCount} 位合作者`,
    color: node.contactCount > 0
      ? { background: '#dbeafe', border: '#4f6ef7', highlight: { background: '#bfdbfe', border: '#3155d9' } }
      : { background: '#f1f5f9', border: '#94a3b8', highlight: { background: '#e2e8f0', border: '#64748b' } },
  }))
}

function visEdges(): Edge[] {
  return props.edges
    .filter((edge) => visibleNodeIds.value.has(edge.source) && visibleNodeIds.value.has(edge.target))
    .map((edge) => ({
      id: `${edge.source}:${edge.target}`,
      from: edge.source,
      to: edge.target,
      value: Math.max(1, edge.sharedPaperCount),
      title: `共同论文 ${edge.sharedPaperCount} 篇`,
      ...(edge.sharedPaperCount > 1 ? { label: String(edge.sharedPaperCount) } : {}),
      font: { color: '#64748b', size: 10, strokeWidth: 4, strokeColor: '#ffffff' },
    }))
}

async function createNetwork(): Promise<void> {
  destroyNetwork()
  if (props.loading || props.error || props.nodes.length === 0) return
  await nextTick()
  if (!graphElement.value) return
  network = new Network(
    graphElement.value,
    { nodes: new DataSet(visNodes()), edges: new DataSet(visEdges()) },
    graphOptions.value,
  )
  network.on('selectNode', (event?: { nodes?: Array<string | number> }) => {
    selectedAuthorId.value = String(event?.nodes?.[0] ?? '')
  })
  network.on('deselectNode', () => {
    selectedAuthorId.value = ''
  })
  network.on('startStabilizing', () => {
    stabilizing.value = true
    stabilizationProgress.value = 0
  })
  network.on('stabilizationProgress', (event?: { iterations?: number; total?: number }) => {
    const total = event?.total ?? 0
    stabilizationProgress.value = total > 0 ? Math.round(((event?.iterations ?? 0) / total) * 100) : 0
  })
  network.on('stabilized', () => {
    stabilizing.value = false
    stabilizationProgress.value = 100
  })
}

function destroyNetwork(): void {
  network?.destroy()
  network = null
}

function togglePhysics(): void {
  physicsEnabled.value = !physicsEnabled.value
  if (physicsEnabled.value) {
    network?.setOptions({ physics: { enabled: true } })
    network?.startSimulation()
  } else {
    network?.stopSimulation()
    network?.setOptions({ physics: { enabled: false } })
    stabilizing.value = false
  }
}

function recompute(): void {
  if (!network) return
  physicsEnabled.value = true
  stabilizing.value = true
  stabilizationProgress.value = 0
  network.setOptions({ physics: { enabled: true } })
  network.stabilize(500)
}

function fit(): void {
  network?.fit({ animation: { duration: 450, easingFunction: 'easeInOutQuad' } })
}

function focusSelectedAuthor(): void {
  if (!network || !selectedAuthorId.value) return
  network.selectNodes([selectedAuthorId.value])
  network.focus(selectedAuthorId.value, {
    scale: 1.15,
    animation: { duration: 450, easingFunction: 'easeInOutQuad' },
  })
}

onMounted(createNetwork)
watch(() => [props.nodes, props.edges, props.loading, props.error], createNetwork, { deep: true })
onBeforeUnmount(destroyNetwork)
</script>

<template>
  <section class="overflow-hidden rounded-xl bg-white shadow-xs ring-1 ring-slate-200">
    <div class="border-b border-slate-100 px-5 py-4 sm:flex sm:items-start sm:justify-between sm:gap-4">
      <div>
        <div class="flex items-center gap-2">
          <span class="grid size-8 place-items-center rounded-lg bg-brand-50 text-brand-600">
            <SparklesIcon class="size-4" />
          </span>
          <div>
            <h2 class="text-sm font-semibold text-slate-950">
              作者协作物理图谱
            </h2>
            <p class="mt-0.5 text-xs text-slate-500">
              节点越大代表论文越多，连线越粗代表共同论文越多。
            </p>
          </div>
        </div>
      </div>
      <div class="mt-3 flex flex-wrap items-center gap-2 sm:mt-0 sm:justify-end">
        <label
          class="sr-only"
          for="author-network-search"
        >搜索作者</label>
        <select
          id="author-network-search"
          v-model="selectedAuthorId"
          aria-label="搜索作者"
          class="min-h-9 max-w-56 rounded-md border-0 bg-white px-2.5 text-xs text-slate-700 ring-1 ring-slate-300 ring-inset focus:ring-2 focus:ring-brand-500"
          :disabled="nodes.length === 0"
          @change="focusSelectedAuthor"
        >
          <option value="">
            搜索或选择作者
          </option>
          <option
            v-for="node in nodes"
            :key="node.id"
            :value="node.id"
          >
            {{ node.label }}
          </option>
        </select>
        <button
          type="button"
          class="inline-flex min-h-9 items-center gap-1.5 rounded-md px-2.5 text-xs font-medium text-slate-600 ring-1 ring-slate-200 ring-inset hover:bg-slate-50 disabled:opacity-40"
          :disabled="nodes.length === 0"
          :aria-label="physicsEnabled ? '暂停物理计算' : '启用物理计算'"
          @click="togglePhysics"
        >
          <PauseIcon
            v-if="physicsEnabled"
            class="size-4"
          />
          <PlayIcon
            v-else
            class="size-4"
          />
          {{ physicsEnabled ? '暂停物理' : '启用物理' }}
        </button>
        <button
          type="button"
          class="inline-flex min-h-9 items-center gap-1.5 rounded-md px-2.5 text-xs font-medium text-slate-600 ring-1 ring-slate-200 ring-inset hover:bg-slate-50 disabled:opacity-40"
          :disabled="nodes.length === 0"
          aria-label="重新计算关系布局"
          @click="recompute"
        >
          <SparklesIcon class="size-4" />重算
        </button>
        <button
          type="button"
          class="inline-flex min-h-9 items-center gap-1.5 rounded-md px-2.5 text-xs font-medium text-slate-600 ring-1 ring-slate-200 ring-inset hover:bg-slate-50 disabled:opacity-40"
          :disabled="nodes.length === 0"
          aria-label="适应画布"
          @click="fit"
        >
          <ArrowsPointingOutIcon class="size-4" />适应画布
        </button>
      </div>
    </div>

    <div
      v-if="loading"
      class="grid h-[590px] animate-pulse place-items-center bg-slate-50 text-sm text-slate-400"
    >
      正在构建作者关系…
    </div>
    <div
      v-else-if="error"
      class="grid h-[590px] place-items-center bg-rose-50 px-6 text-center text-sm text-rose-700"
    >
      {{ error }}
    </div>
    <div
      v-else-if="nodes.length === 0"
      class="grid h-[590px] place-items-center bg-slate-50 px-6 text-center"
    >
      <div>
        <SparklesIcon class="mx-auto size-9 text-slate-300" />
        <p class="mt-3 text-sm font-medium text-slate-700">
          当前筛选范围没有可展示的作者关系
        </p>
        <p class="mt-1 text-xs text-slate-500">
          扩大导入日期或分类范围后再试。
        </p>
      </div>
    </div>
    <div
      v-else
      class="grid lg:grid-cols-[minmax(0,1fr)_280px]"
    >
      <div class="relative min-h-[590px] bg-[radial-gradient(circle_at_center,_#ffffff_0,_#f8fafc_72%)]">
        <div
          ref="graphElement"
          class="absolute inset-0 min-h-[590px] outline-none"
          role="img"
          :aria-label="`作者关系图谱，${nodes.length} 个作者，${edges.length} 条协作关系`"
        />
        <div class="pointer-events-none absolute bottom-4 left-4 flex flex-wrap gap-2 text-[11px] text-slate-500">
          <span class="rounded-full bg-white/90 px-2.5 py-1 shadow-xs ring-1 ring-slate-200">蓝色：已映射联系人</span>
          <span class="rounded-full bg-white/90 px-2.5 py-1 shadow-xs ring-1 ring-slate-200">灰色：暂无联系人</span>
        </div>
        <div
          v-if="stabilizing"
          class="absolute left-4 top-4 w-44 rounded-lg bg-white/95 p-3 shadow-sm ring-1 ring-slate-200"
        >
          <div class="flex justify-between text-[11px] font-medium text-slate-600">
            <span>物理计算中</span><span>{{ stabilizationProgress }}%</span>
          </div>
          <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-slate-100">
            <div
              class="h-full rounded-full bg-brand-500 transition-[width]"
              :style="{ width: `${stabilizationProgress}%` }"
            />
          </div>
        </div>
      </div>

      <aside class="border-t border-slate-100 bg-slate-50/70 p-5 lg:border-l lg:border-t-0">
        <template v-if="selectedAuthor">
          <p class="text-[11px] font-semibold uppercase tracking-[0.14em] text-brand-600">
            选中作者
          </p>
          <h3 class="mt-2 break-words text-lg font-semibold text-slate-950">
            {{ selectedAuthor.label }}
          </h3>
          <dl class="mt-4 grid grid-cols-3 gap-2">
            <div class="rounded-lg bg-white p-2.5 ring-1 ring-slate-200">
              <dt class="text-[10px] text-slate-500">
                论文
              </dt><dd class="mt-1 text-lg font-semibold text-slate-900">
                {{ selectedAuthor.paperCount }}
              </dd>
            </div>
            <div class="rounded-lg bg-white p-2.5 ring-1 ring-slate-200">
              <dt class="text-[10px] text-slate-500">
                合作者
              </dt><dd class="mt-1 text-lg font-semibold text-slate-900">
                {{ selectedAuthor.collaboratorCount }}
              </dd>
            </div>
            <div class="rounded-lg bg-white p-2.5 ring-1 ring-slate-200">
              <dt class="text-[10px] text-slate-500">
                联系人
              </dt><dd class="mt-1 text-lg font-semibold text-slate-900">
                {{ selectedAuthor.contactCount }}
              </dd>
            </div>
          </dl>
          <div class="mt-5">
            <h4 class="text-xs font-semibold text-slate-700">
              最强可见协作
            </h4>
            <ul
              v-if="strongestNeighbors.length"
              class="mt-2 space-y-2"
            >
              <li
                v-for="item in strongestNeighbors"
                :key="item.node.id"
                class="flex items-center justify-between gap-2 rounded-lg bg-white px-3 py-2 ring-1 ring-slate-200"
              >
                <span class="min-w-0 truncate text-xs font-medium text-slate-700">{{ item.node.label }}</span>
                <span class="shrink-0 text-[11px] text-slate-500">共同论文 {{ item.count }}</span>
              </li>
            </ul>
            <p
              v-else
              class="mt-2 text-xs/5 text-slate-500"
            >
              该作者在当前范围内没有共同作者。
            </p>
          </div>
        </template>
        <div
          v-else
          class="grid min-h-52 place-items-center text-center"
        >
          <div>
            <SparklesIcon class="mx-auto size-7 text-slate-300" />
            <p class="mt-3 text-sm font-medium text-slate-700">
              选择一个作者节点
            </p>
            <p class="mt-1 text-xs/5 text-slate-500">
              查看论文量、合作者数量和最强协作关系。
            </p>
          </div>
        </div>
      </aside>
    </div>
  </section>
</template>
