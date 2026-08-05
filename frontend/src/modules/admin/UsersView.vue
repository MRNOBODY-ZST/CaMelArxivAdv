<script setup lang="ts">
import { PlusIcon, UserGroupIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, reactive, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsCheckbox from '@/components/design-skill/DsCheckbox.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import DsSelect from '@/components/design-skill/DsSelect.vue'
import DsSkeleton from '@/components/design-skill/DsSkeleton.vue'
import DsTable from '@/components/design-skill/DsTable.vue'
import { adminApi, administrationErrorMessage, type RoleView, type UserView } from '@/modules/admin/admin.api'
import { useAuthStore } from '@/modules/auth/auth.store'

const auth = useAuthStore()
const users = ref<UserView[]>([])
const page = ref(1)
const totalPages = ref(0)
const total = ref(0)
const search = ref('')
const status = ref('')
const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')
const disableCandidate = ref<UserView | null>(null)
const createOpen = ref(false)
const editOpen = ref(false)
const editingUser = ref<UserView | null>(null)
const resetCandidate = ref<UserView | null>(null)
const resetPasswordValue = ref('')
const availableRoles = ref<RoleView[]>([])
const createForm = reactive({
  username: '', email: '', displayName: '', initialPassword: '', roleCodes: [] as string[],
})
const editForm = reactive({ email: '', displayName: '', roleCodes: [] as string[] })
const assignableRoles = computed(() => availableRoles.value.filter((role) =>
  role.code !== 'SUPER_ADMIN' || auth.user?.roles.includes('SUPER_ADMIN')))

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '正常', value: 'ACTIVE' },
  { label: '已停用', value: 'DISABLED' },
  { label: '已锁定', value: 'LOCKED' },
]

onMounted(() => load())

async function load(targetPage = page.value): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await adminApi.listUsers({
      page: targetPage, pageSize: 20, search: search.value, status: status.value,
    })
    users.value = response.items
    page.value = response.page
    total.value = response.total
    totalPages.value = response.totalPages
  } catch (error) {
    errorMessage.value = administrationErrorMessage(error, '没有权限读取用户数据。')
  } finally {
    loading.value = false
  }
}

async function confirmDisable(): Promise<void> {
  if (!disableCandidate.value) return
  saving.value = true
  try {
    await adminApi.disableUser(disableCandidate.value.id)
    disableCandidate.value = null
    await load()
  } catch (error) {
    errorMessage.value = administrationErrorMessage(error, '没有权限停用用户。')
  } finally {
    saving.value = false
  }
}

async function enableUser(user: UserView): Promise<void> {
  saving.value = true
  try {
    await adminApi.enableUser(user.id)
    await load()
  } catch (error) {
    errorMessage.value = administrationErrorMessage(error, '没有权限启用用户。')
  } finally {
    saving.value = false
  }
}

async function openCreate(): Promise<void> {
  createOpen.value = true
  await loadRoles()
}

async function loadRoles(): Promise<void> {
  if (availableRoles.value.length > 0) return
  try {
    availableRoles.value = await adminApi.listRoles()
  } catch (error) {
    errorMessage.value = administrationErrorMessage(error, '没有权限读取角色数据。')
  }
}

function toggleRole(target: 'create' | 'edit', code: string, selected: boolean): void {
  const form = target === 'create' ? createForm : editForm
  form.roleCodes = selected
    ? [...new Set([...form.roleCodes, code])]
    : form.roleCodes.filter((value) => value !== code)
}

async function createUser(): Promise<void> {
  saving.value = true
  try {
    await adminApi.createUser({ ...createForm })
    createOpen.value = false
    Object.assign(createForm, { username: '', email: '', displayName: '', initialPassword: '', roleCodes: [] })
    await load(1)
  } catch (error) {
    errorMessage.value = administrationErrorMessage(error, '没有权限创建用户。')
  } finally {
    saving.value = false
  }
}

async function openEdit(user: UserView): Promise<void> {
  editingUser.value = user
  Object.assign(editForm, {
    email: user.email, displayName: user.displayName, roleCodes: [...user.roles],
  })
  editOpen.value = true
  await loadRoles()
}

async function updateUser(): Promise<void> {
  if (!editingUser.value) return
  saving.value = true
  try {
    await adminApi.updateUser(editingUser.value.id, { ...editForm })
    editOpen.value = false
    editingUser.value = null
    await load()
  } catch (error) {
    errorMessage.value = administrationErrorMessage(error, '没有权限更新用户。')
  } finally {
    saving.value = false
  }
}

function openReset(user: UserView): void {
  resetCandidate.value = user
  resetPasswordValue.value = ''
}

function canManageUser(user: UserView, permission: 'user:update' | 'user:disable'): boolean {
  const targetIsSuperAdmin = user.roles.includes('SUPER_ADMIN')
  const actorIsSuperAdmin = auth.user?.roles.includes('SUPER_ADMIN') ?? false
  return auth.hasPermission(permission) && (!targetIsSuperAdmin || actorIsSuperAdmin)
}

async function confirmResetPassword(): Promise<void> {
  if (!resetCandidate.value || !resetPasswordValue.value) return
  saving.value = true
  try {
    await adminApi.resetPassword(resetCandidate.value.id, resetPasswordValue.value)
    resetCandidate.value = null
    resetPasswordValue.value = ''
    await load()
  } catch (error) {
    errorMessage.value = administrationErrorMessage(error, '没有权限重置密码。')
  } finally {
    saving.value = false
  }
}

function statusTone(statusValue: UserView['status']): 'positive' | 'warning' | 'danger' {
  if (statusValue === 'ACTIVE') return 'positive'
  return statusValue === 'LOCKED' ? 'warning' : 'danger'
}

function dateTime(value: string | null): string {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '从未登录'
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
          用户管理
        </h1>
        <p class="mt-2 text-sm/6 text-slate-500">
          管理账号状态和角色。状态、角色及密码变化会立即使旧会话失效。
        </p>
      </div>
      <DsButton
        v-if="auth.hasPermission('user:create')"
        data-testid="create-user"
        @click="openCreate"
      >
        <PlusIcon class="size-4" />创建用户
      </DsButton>
    </div>

    <DsAlert
      v-if="errorMessage"
      tone="danger"
      title="用户数据加载失败"
    >
      {{ errorMessage }}
    </DsAlert>

    <DsCard>
      <form
        class="grid gap-3 sm:grid-cols-[minmax(0,1fr)_12rem_auto]"
        @submit.prevent="load(1)"
      >
        <DsInput
          id="user-search"
          v-model="search"
          type="search"
          placeholder="搜索用户名、邮箱或显示名称"
        />
        <DsSelect
          id="user-status"
          v-model="status"
          :options="statusOptions"
        />
        <DsButton
          type="submit"
          variant="secondary"
        >
          筛选
        </DsButton>
      </form>
      <p class="mt-3 text-xs text-slate-500">
        共 {{ total }} 个用户
      </p>
    </DsCard>

    <DsCard padding="none">
      <div
        v-if="loading"
        data-testid="users-skeleton"
        class="space-y-3 p-6"
      >
        <DsSkeleton
          v-for="index in 5"
          :key="index"
          class="h-12"
        />
      </div>
      <DsEmptyState
        v-else-if="users.length === 0"
        title="暂无用户"
        description="调整筛选条件，或创建第一个业务用户。"
      >
        <template #icon>
          <UserGroupIcon class="size-8 text-slate-400" />
        </template>
      </DsEmptyState>
      <template v-else>
        <DsTable label="用户列表">
          <thead class="bg-slate-50 text-xs font-semibold text-slate-500">
            <tr>
              <th class="px-5 py-3">
                用户
              </th><th class="px-5 py-3">
                角色
              </th><th class="px-5 py-3">
                状态
              </th><th class="px-5 py-3">
                最近登录
              </th><th class="px-5 py-3 text-right">
                操作
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            <tr
              v-for="user in users"
              :key="user.id"
            >
              <td class="px-5 py-4">
                <p class="font-medium text-slate-900">
                  {{ user.displayName }}
                </p><p class="mt-0.5 text-xs text-slate-500">
                  {{ user.username }} · {{ user.email }}
                </p>
              </td>
              <td class="px-5 py-4">
                <div class="flex flex-wrap gap-1">
                  <DsBadge
                    v-for="role in user.roles"
                    :key="role"
                  >
                    {{ role }}
                  </DsBadge>
                </div>
              </td>
              <td class="px-5 py-4">
                <DsBadge
                  :tone="statusTone(user.status)"
                  dot
                >
                  {{ user.status }}
                </DsBadge><p
                  v-if="user.forcePasswordChange"
                  class="mt-1 text-xs text-amber-700"
                >
                  需修改密码
                </p>
              </td>
              <td class="whitespace-nowrap px-5 py-4 text-slate-600">
                {{ dateTime(user.lastLoginAt) }}
              </td>
              <td class="px-5 py-4 text-right">
                <div class="flex justify-end gap-1">
                  <DsButton
                    v-if="canManageUser(user, 'user:update')"
                    data-testid="edit-user"
                    size="sm"
                    variant="ghost"
                    @click="openEdit(user)"
                  >
                    编辑
                  </DsButton>
                  <DsButton
                    v-if="canManageUser(user, 'user:update')"
                    data-testid="reset-user-password"
                    size="sm"
                    variant="ghost"
                    @click="openReset(user)"
                  >
                    重置密码
                  </DsButton>
                  <DsButton
                    v-if="user.status === 'ACTIVE' && canManageUser(user, 'user:disable')"
                    data-testid="disable-user"
                    size="sm"
                    variant="ghost"
                    @click="disableCandidate = user"
                  >
                    停用
                  </DsButton>
                  <DsButton
                    v-else-if="user.status !== 'ACTIVE' && canManageUser(user, 'user:disable')"
                    size="sm"
                    variant="ghost"
                    @click="enableUser(user)"
                  >
                    启用
                  </DsButton>
                </div>
              </td>
            </tr>
          </tbody>
        </DsTable>
        <div
          v-if="totalPages > 1"
          class="px-5 pb-5"
        >
          <DsPagination
            :page="page"
            :total-pages="totalPages"
            @change="load"
          />
        </div>
      </template>
    </DsCard>

    <DsModal
      :open="Boolean(disableCandidate)"
      title="确认停用用户"
      :description="disableCandidate ? `停用 ${disableCandidate.displayName} 后，其 access token 与 refresh 会话会立即失效。` : ''"
      @close="disableCandidate = null"
    >
      <DsAlert tone="warning">
        若这是最后一个活跃 SUPER_ADMIN，后端会拒绝此操作。
      </DsAlert>
      <template #actions>
        <DsButton
          variant="secondary"
          @click="disableCandidate = null"
        >
          取消
        </DsButton><DsButton
          data-testid="confirm-disable-user"
          variant="danger"
          :busy="saving"
          @click="confirmDisable"
        >
          确认停用
        </DsButton>
      </template>
    </DsModal>

    <DsModal
      :open="createOpen"
      title="创建用户"
      description="初始密码只在此处提交，创建后不会再次显示。"
      @close="createOpen = false"
    >
      <form
        id="create-user-form"
        class="space-y-4"
        @submit.prevent="createUser"
      >
        <DsInput
          id="create-username"
          v-model="createForm.username"
          label="用户名"
        />
        <DsInput
          id="create-email"
          v-model="createForm.email"
          type="email"
          label="邮箱"
        />
        <DsInput
          id="create-display-name"
          v-model="createForm.displayName"
          label="显示名称"
        />
        <DsInput
          id="create-initial-password"
          v-model="createForm.initialPassword"
          type="password"
          label="初始密码"
          autocomplete="new-password"
        />
        <fieldset class="space-y-3">
          <legend class="text-sm font-medium text-slate-900">
            角色
          </legend><DsCheckbox
            v-for="role in assignableRoles"
            :id="`create-role-${role.code}`"
            :key="role.id"
            :model-value="createForm.roleCodes.includes(role.code)"
            :label="role.name"
            :description="role.code"
            @update:model-value="toggleRole('create', role.code, $event)"
          />
        </fieldset>
      </form>
      <template #actions>
        <DsButton
          variant="secondary"
          @click="createOpen = false"
        >
          取消
        </DsButton><DsButton
          type="submit"
          form="create-user-form"
          :busy="saving"
        >
          创建
        </DsButton>
      </template>
    </DsModal>

    <DsModal
      :open="editOpen"
      title="编辑用户"
      :description="editingUser ? `更新 ${editingUser.username} 的资料和角色会立即使其旧会话失效。` : ''"
      @close="editOpen = false"
    >
      <form
        id="edit-user-form"
        class="space-y-4"
        @submit.prevent="updateUser"
      >
        <DsInput
          id="edit-user-email"
          v-model="editForm.email"
          type="email"
          label="邮箱"
        />
        <DsInput
          id="edit-user-display-name"
          v-model="editForm.displayName"
          label="显示名称"
        />
        <fieldset class="space-y-3">
          <legend class="text-sm font-medium text-slate-900">
            角色
          </legend>
          <DsCheckbox
            v-for="role in assignableRoles"
            :id="`edit-role-${role.code}`"
            :key="role.id"
            :model-value="editForm.roleCodes.includes(role.code)"
            :label="role.name"
            :description="role.code"
            @update:model-value="toggleRole('edit', role.code, $event)"
          />
        </fieldset>
      </form>
      <template #actions>
        <DsButton
          variant="secondary"
          @click="editOpen = false"
        >
          取消
        </DsButton>
        <DsButton
          data-testid="confirm-edit-user"
          type="submit"
          form="edit-user-form"
          :busy="saving"
        >
          保存
        </DsButton>
      </template>
    </DsModal>

    <DsModal
      :open="Boolean(resetCandidate)"
      title="重置用户密码"
      :description="resetCandidate ? `${resetCandidate.username} 的全部会话会失效，且下次登录必须再次修改密码。` : ''"
      @close="resetCandidate = null"
    >
      <DsInput
        id="reset-user-new-password"
        v-model="resetPasswordValue"
        type="password"
        label="新临时密码"
        autocomplete="new-password"
        description="至少 12 位，包含大写、小写、数字和符号。"
      />
      <template #actions>
        <DsButton
          variant="secondary"
          @click="resetCandidate = null"
        >
          取消
        </DsButton>
        <DsButton
          data-testid="confirm-reset-user-password"
          variant="danger"
          :busy="saving"
          :disabled="!resetPasswordValue"
          @click="confirmResetPassword"
        >
          确认重置
        </DsButton>
      </template>
    </DsModal>
  </div>
</template>
