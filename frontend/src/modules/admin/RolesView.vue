<script setup lang="ts">
import { KeyIcon, PlusIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, reactive, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsCheckbox from '@/components/design-skill/DsCheckbox.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import DsSkeleton from '@/components/design-skill/DsSkeleton.vue'
import DsTable from '@/components/design-skill/DsTable.vue'
import { adminApi, administrationErrorMessage, type PermissionView, type RoleView } from '@/modules/admin/admin.api'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'

const auth = useAuthStore()
const roles = ref<RoleView[]>([])
const permissionCatalog = ref<PermissionView[]>([])
const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')
const editorOpen = ref(false)
const editingId = ref<string | null>(null)
const editingRole = ref<RoleView | null>(null)
const deleteCandidate = ref<RoleView | null>(null)
const form = reactive({ code: '', name: '', description: '', permissionCodes: [] as Permission[] })
const editingSystemRole = computed(() => editingRole.value?.systemRole === true)
const editingSuperAdmin = computed(() => editingRole.value?.code === 'SUPER_ADMIN')

onMounted(() => load())

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [roleItems, permissions] = await Promise.all([adminApi.listRoles(), adminApi.listPermissions()])
    roles.value = roleItems
    permissionCatalog.value = permissions
  } catch (error) {
    errorMessage.value = administrationErrorMessage(error, '没有权限读取角色数据。')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingId.value = null
  editingRole.value = null
  Object.assign(form, { code: '', name: '', description: '', permissionCodes: [] })
  editorOpen.value = true
}

function openEdit(role: RoleView): void {
  editingId.value = role.id
  editingRole.value = role
  Object.assign(form, {
    code: role.code, name: role.name, description: role.description,
    permissionCodes: [...role.permissions],
  })
  editorOpen.value = true
}

async function confirmDelete(): Promise<void> {
  if (!deleteCandidate.value) return
  saving.value = true
  errorMessage.value = ''
  try {
    await adminApi.deleteRole(deleteCandidate.value.id)
    deleteCandidate.value = null
    await load()
  } catch (error) {
    errorMessage.value = administrationErrorMessage(error, '没有权限删除角色。')
  } finally {
    saving.value = false
  }
}

function togglePermission(code: Permission, selected: boolean): void {
  form.permissionCodes = selected
    ? [...new Set([...form.permissionCodes, code])]
    : form.permissionCodes.filter((value) => value !== code)
}

async function save(): Promise<void> {
  saving.value = true
  errorMessage.value = ''
  try {
    const request = { ...form, permissionCodes: [...form.permissionCodes] }
    if (editingId.value) await adminApi.updateRole(editingId.value, request)
    else await adminApi.createRole(request)
    editorOpen.value = false
    await load()
  } catch (error) {
    errorMessage.value = administrationErrorMessage(error, '没有权限管理角色。')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p class="text-xs font-semibold uppercase tracking-wider text-brand-600">
          系统管理
        </p>
        <h1 class="mt-1 text-2xl font-semibold tracking-tight text-slate-950">
          角色与权限
        </h1>
        <p class="mt-2 text-sm/6 text-slate-500">
          权限代码由系统定义；角色变更会立即失效所有关联用户会话。
        </p>
      </div>
      <DsButton
        v-if="auth.hasPermission('role:manage')"
        data-testid="create-role"
        @click="openCreate"
      >
        <PlusIcon class="size-4" />创建角色
      </DsButton>
    </div>

    <DsAlert
      v-if="errorMessage"
      tone="danger"
      title="角色数据加载失败"
    >
      {{ errorMessage }}
    </DsAlert>

    <div
      v-if="loading"
      class="grid gap-4 lg:grid-cols-2"
    >
      <DsCard
        v-for="index in 4"
        :key="index"
      >
        <DsSkeleton class="h-6 w-40" /><DsSkeleton class="mt-4 h-16" />
      </DsCard>
    </div>
    <DsCard v-else-if="roles.length === 0">
      <DsEmptyState
        title="暂无自定义角色"
        description="系统角色始终由迁移初始化；可在这里添加职责更窄的角色。"
      >
        <template #icon>
          <KeyIcon class="size-8 text-slate-400" />
        </template>
      </DsEmptyState>
    </DsCard>
    <DsCard
      v-else
      padding="none"
    >
      <DsTable label="角色列表">
        <thead class="bg-slate-50 text-xs font-semibold text-slate-500">
          <tr>
            <th class="px-5 py-3">
              角色
            </th><th class="px-5 py-3">
              用户
            </th><th class="px-5 py-3">
              权限
            </th><th class="px-5 py-3 text-right">
              操作
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          <tr
            v-for="role in roles"
            :key="role.id"
          >
            <td class="px-5 py-4">
              <div class="flex items-center gap-2">
                <p class="font-medium text-slate-900">
                  {{ role.name }}
                </p><DsBadge
                  v-if="role.systemRole"
                  tone="info"
                >
                  系统
                </DsBadge>
              </div><p class="mt-1 font-mono text-xs text-slate-500">
                {{ role.code }}
              </p><p class="mt-1 max-w-sm text-xs/5 text-slate-500">
                {{ role.description }}
              </p>
            </td>
            <td class="px-5 py-4 text-slate-600">
              {{ role.userCount }}
            </td>
            <td class="px-5 py-4">
              <div class="flex max-w-xl flex-wrap gap-1">
                <DsBadge
                  v-for="permission in role.permissions.slice(0, 8)"
                  :key="permission"
                >
                  {{ permission }}
                </DsBadge><DsBadge v-if="role.permissions.length > 8">
                  +{{ role.permissions.length - 8 }}
                </DsBadge>
              </div>
            </td>
            <td class="px-5 py-4 text-right">
              <div class="flex justify-end gap-1">
                <DsButton
                  v-if="auth.hasPermission('role:manage')"
                  size="sm"
                  variant="ghost"
                  @click="openEdit(role)"
                >
                  编辑
                </DsButton>
                <DsButton
                  v-if="auth.hasPermission('role:manage') && !role.systemRole && role.userCount === 0"
                  data-testid="delete-role"
                  size="sm"
                  variant="ghost"
                  @click="deleteCandidate = role"
                >
                  删除
                </DsButton>
              </div>
            </td>
          </tr>
        </tbody>
      </DsTable>
    </DsCard>

    <DsModal
      :open="editorOpen"
      :title="editingId ? '编辑角色' : '创建角色'"
      description="只授予完成职责所需的最小权限。系统角色代码不可修改。"
      @close="editorOpen = false"
    >
      <form
        id="role-editor"
        class="space-y-4"
        @submit.prevent="save"
      >
        <DsInput
          id="role-code"
          v-model="form.code"
          label="角色代码"
          description="大写字母、数字和下划线，例如 RESEARCH_ADMIN。"
          :disabled="editingSystemRole"
        />
        <DsInput
          id="role-name"
          v-model="form.name"
          label="名称"
        />
        <DsInput
          id="role-description"
          v-model="form.description"
          label="说明"
        />
        <fieldset class="max-h-72 space-y-3 overflow-y-auto rounded-lg border border-slate-200 p-4">
          <legend class="px-1 text-sm font-medium text-slate-900">
            权限
          </legend>
          <DsCheckbox
            v-for="permission in permissionCatalog"
            :id="`permission-${permission.code.replaceAll(':', '-')}`"
            :key="permission.id"
            :model-value="form.permissionCodes.includes(permission.code)"
            :label="permission.code"
            :description="permission.description"
            :disabled="editingSuperAdmin"
            @update:model-value="togglePermission(permission.code, $event)"
          />
        </fieldset>
      </form>
      <template #actions>
        <DsButton
          variant="secondary"
          @click="editorOpen = false"
        >
          取消
        </DsButton><DsButton
          type="submit"
          form="role-editor"
          :busy="saving"
        >
          保存
        </DsButton>
      </template>
    </DsModal>

    <DsModal
      :open="Boolean(deleteCandidate)"
      title="删除自定义角色"
      :description="deleteCandidate ? `确认删除未分配的角色 ${deleteCandidate.code}？此操作会写入审计日志。` : ''"
      @close="deleteCandidate = null"
    >
      <DsAlert tone="warning">
        系统角色和仍分配给用户的角色不能删除。
      </DsAlert>
      <template #actions>
        <DsButton
          variant="secondary"
          @click="deleteCandidate = null"
        >
          取消
        </DsButton>
        <DsButton
          data-testid="confirm-delete-role"
          variant="danger"
          :busy="saving"
          @click="confirmDelete"
        >
          确认删除
        </DsButton>
      </template>
    </DsModal>
  </div>
</template>
